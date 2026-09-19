/* JNI shim between the UxPlay mirroring core and the Kotlin app.
 * Video frames arrive here as decrypted Annex-B H.264 on the lib's RTP
 * threads; we copy them into jbyteArrays (the lib frees the buffer on
 * return) and hand them to the MediaCodec-backed listener. */
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <android/log.h>
#include "raop.h"
#include "stream.h"
#include "logger.h"
#include "alac_decoder.h"

#define TAG "Wormhole"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM *g_vm;
static jobject g_listener;                          /* guarded by g_listener_lock */
static pthread_mutex_t g_listener_lock = PTHREAD_MUTEX_INITIALIZER;
static jmethodID g_on_video_frame, g_on_audio_frame, g_on_audio_volume, g_on_audio_flush,
                 g_on_client_connected, g_on_client_disconnected,
                 g_on_mirror_running, g_on_audio_running, g_on_stream_error;
static raop_t *g_raop;                              /* guarded by g_lock */
static dnssd_t *g_dnssd;                            /* guarded by g_lock */
static bool g_allow_hevc;                           /* immutable while server workers run */
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_key_t g_tls_key;                     /* set on threads this shim attached */

/* Audio codec negotiated by the current session (AirPlay "ct": 2 = ALAC, 8 = AAC-ELD).
 * ALAC (Music / iTunes audio-only, iOS audio-only) is decoded here because the
 * Portal has no MediaCodec ALAC decoder; Kotlin receives PCM tagged AUDIO_CT_PCM. */
#define AUDIO_CT_PCM 0
#define AUDIO_CT_ALAC 2
#define AUDIO_CT_AAC_ELD 8
static pthread_mutex_t g_audio_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_audio_ct = AUDIO_CT_AAC_ELD;            /* guarded by g_audio_lock */
static unsigned short g_audio_spf = 480;             /* guarded by g_audio_lock */
static alac_decoder_t *g_alac;                       /* guarded by g_audio_lock */
static int16_t *g_pcm;                               /* guarded by g_audio_lock */
static size_t g_pcm_samples;                         /* guarded by g_audio_lock */
static int g_alac_errors;                            /* guarded by g_audio_lock */

/* Threads attached by env_for_thread must be detached before they exit,
 * otherwise the JVM retains per-thread resources (the core creates and
 * terminates its own media/control workers). */
static void tls_detach(void *arg) {
    (*g_vm)->DetachCurrentThread(g_vm);
}

static JNIEnv *env_for_thread(void) {
    JNIEnv *env = NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return NULL;
        if (pthread_setspecific(g_tls_key, (void *) 1) != 0) {
            (*g_vm)->DetachCurrentThread(g_vm);
            return NULL;
        }
    }
    return env;
}

/* Snapshot the listener under its lock so nativeSetListener can never delete
 * the global reference between a callback's null check and the actual call. */
static jobject listener_snapshot(JNIEnv *env) {
    jobject ref = NULL;
    pthread_mutex_lock(&g_listener_lock);
    if (g_listener) ref = (*env)->NewLocalRef(env, g_listener);
    pthread_mutex_unlock(&g_listener_lock);
    return ref;
}

static void call_void(jmethodID mid, jobject arg) {
    JNIEnv *env = env_for_thread();
    if (!env) return;
    jobject listener = listener_snapshot(env);
    if (!listener) return;
    (*env)->CallVoidMethod(env, listener, mid, arg);
    (*env)->DeleteLocalRef(env, listener);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
}

static void call_bool(jmethodID mid, jboolean value) {
    JNIEnv *env = env_for_thread();
    if (!env) return;
    jobject listener = listener_snapshot(env);
    if (!listener) return;
    (*env)->CallVoidMethod(env, listener, mid, value);
    (*env)->DeleteLocalRef(env, listener);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
}

static void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    if (!data || !data->data || data->data_len <= 0 || (data->is_h265 && !g_allow_hevc)) return;
    /* Android System.nanoTime uses CLOCK_MONOTONIC too. NTP fields below are
     * separate wall-clock metadata and must never drive local stage timings. */
    struct timespec ingress;
    clock_gettime(CLOCK_MONOTONIC, &ingress);
    jlong ingress_ns = (jlong) ingress.tv_sec * 1000000000LL + ingress.tv_nsec;
    JNIEnv *env = env_for_thread();
    if (!env) return;
    jobject listener = listener_snapshot(env);
    if (!listener) return;
    jbyteArray frame = (*env)->NewByteArray(env, data->data_len);
    if (!frame) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, listener);
        return;
    }
    (*env)->SetByteArrayRegion(env, frame, 0, data->data_len, (const jbyte *) data->data);
    (*env)->CallVoidMethod(env, listener, g_on_video_frame, frame, ingress_ns,
                           (jlong) data->ntp_time_local, (jlong) data->ntp_time_remote,
                           data->is_h265 ? JNI_TRUE : JNI_FALSE);
    (*env)->DeleteLocalRef(env, frame);
    (*env)->DeleteLocalRef(env, listener);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
}

static void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    if (!data || !data->data || data->data_len <= 0) return;
    const void *payload = data->data;
    int payload_len = data->data_len;
    int ct = data->ct;

    pthread_mutex_lock(&g_audio_lock);
    if (ct == AUDIO_CT_ALAC) {
        if (!g_alac || !g_pcm) {
            pthread_mutex_unlock(&g_audio_lock);
            return;
        }
        int frames = alac_decoder_decode(g_alac, (const uint8_t *) data->data, (size_t) data->data_len, g_pcm, g_pcm_samples);
        if (frames <= 0) {
            if (g_alac_errors++ < 5) LOGW("ALAC decode failed (%d) on a %d byte packet", frames, data->data_len);
            pthread_mutex_unlock(&g_audio_lock);
            return;
        }
        payload = g_pcm;
        payload_len = frames * alac_decoder_channels(g_alac) * (int) sizeof(int16_t);
        ct = AUDIO_CT_PCM;
    }

    JNIEnv *env = env_for_thread();
    if (!env) { pthread_mutex_unlock(&g_audio_lock); return; }
    jobject listener = listener_snapshot(env);
    if (!listener) { pthread_mutex_unlock(&g_audio_lock); return; }
    jbyteArray frame = (*env)->NewByteArray(env, payload_len);
    if (!frame) {
        pthread_mutex_unlock(&g_audio_lock);
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, listener);
        return;
    }
    (*env)->SetByteArrayRegion(env, frame, 0, payload_len, (const jbyte *) payload);
    pthread_mutex_unlock(&g_audio_lock);   /* g_pcm has been copied out */
    (*env)->CallVoidMethod(env, listener, g_on_audio_frame, frame, (jlong) data->ntp_time_local, (jint) ct);
    (*env)->DeleteLocalRef(env, frame);
    (*env)->DeleteLocalRef(env, listener);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
}

static void cb_report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    *admit = true;
    LOGI("client request: %s (%s)", name ? name : "?", model ? model : "?");
    JNIEnv *env = env_for_thread();
    if (!env) return;
    jstring jname = (*env)->NewStringUTF(env, name ? name : "Mac");
    call_void(g_on_client_connected, jname);
    (*env)->DeleteLocalRef(env, jname);
}

/* Fires for EVERY control-socket teardown, including rejected second clients
 * and short-lived probes; the Kotlin side treats it as diagnostic only and
 * keys renderer/session state off onMirrorRunning instead. */
static void cb_conn_destroy(void *cls) {
    LOGI("control connection closed");
    call_void(g_on_client_disconnected, NULL);
}

/* Mirror transport failed (conn_reset) or the stream is being torn down
 * (video_reset) while the RTSP socket may remain open: tell the UI so it
 * can reset the decoder instead of freezing on the last image. */
static void cb_conn_reset(void *cls, int reason) {
    LOGW("connection reset by core (reason %d)", reason);
    call_void(g_on_stream_error, NULL);
}

static void cb_video_reset(void *cls, reset_type_t t) {
    LOGW("video reset by core (type %d)", (int) t);
    /* Normal TEARDOWN also invokes video_reset, often before joining the
     * mirror worker. Its running=false callback owns normal session cleanup. */
}

/* Verified mirror-stream lifetime: true when the mirror data thread starts,
 * false when it exits. This, not control-socket lifetime, owns renderer
 * state. */
static void cb_mirror_video_running(void *cls, bool running) {
    LOGI("mirror video %s", running ? "started" : "stopped");
    call_bool(g_on_mirror_running, running ? JNI_TRUE : JNI_FALSE);
}

static void cb_log(void *cls, int level, const char *msg) {
    __android_log_print(level <= LOGGER_ERR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "uxplay", "%s", msg);
}

static int cb_video_set_codec(void *cls, video_codec_t codec) {
    if (codec == VIDEO_CODEC_H265 && !g_allow_hevc) {
        LOGW("client requested H.265 without enabled hardware support; rejecting");
        return -1;  /* the core treats only a negative result as failure */
    }
    LOGI("negotiated video codec: %s", codec == VIDEO_CODEC_H265 ? "H.265" : "H.264");
    return 0;
}

static double cb_audio_get_volume(void *cls) { return 0.0; }
static void cb_stub_void(void *cls) {}
static void cb_stub_flush(void *cls) {}
static void cb_audio_flush(void *cls) {
    JNIEnv *env = env_for_thread();
    if (!env) return;
    jobject listener = listener_snapshot(env);
    if (!listener) return;
    (*env)->CallVoidMethod(env, listener, g_on_audio_flush);
    (*env)->DeleteLocalRef(env, listener);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
}
static void cb_audio_set_volume(void *cls, float v) {
    JNIEnv *env = env_for_thread();
    if (!env) return;
    jobject listener = listener_snapshot(env);
    if (!listener) return;
    (*env)->CallVoidMethod(env, listener, g_on_audio_volume, (jfloat) v);
    (*env)->DeleteLocalRef(env, listener);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
}
static void cb_audio_set_data(void *cls, const void *b, int l) {}
static void cb_audio_remote_control(void *cls, const char *a, const char *b) {}
static void cb_audio_set_progress(void *cls, uint32_t *s, uint32_t *c, uint32_t *e) {}
/* The core reports the format the client negotiated in SETUP; it must not be
 * overridden (an earlier version forced AAC-ELD here, which broke ALAC sessions).
 * Mirroring sends AAC-ELD (ct=8, spf=480); Music / iTunes and iOS audio-only send
 * ALAC (ct=2, spf=352). */
static void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf,
                                bool *usingScreen, bool *isMedia, uint64_t *audioFormat) {
    LOGI("audio format: ct=%u spf=%u usingScreen=%d isMedia=%d audioFormat=0x%llx",
         ct ? *ct : 0, spf ? *spf : 0, usingScreen ? *usingScreen : 0, isMedia ? *isMedia : 0,
         audioFormat ? (unsigned long long) *audioFormat : 0ULL);
    pthread_mutex_lock(&g_audio_lock);
    g_audio_ct = ct ? *ct : AUDIO_CT_AAC_ELD;
    g_audio_spf = (spf && *spf) ? *spf : (g_audio_ct == AUDIO_CT_ALAC ? 352 : 480);
    pthread_mutex_unlock(&g_audio_lock);
}

static void audio_decoder_release_locked(void) {
    alac_decoder_destroy(g_alac);
    g_alac = NULL;
    free(g_pcm);
    g_pcm = NULL;
    g_pcm_samples = 0;
}

/* Verified audio RTP thread lifetime (the audio analogue of cb_mirror_video_running).
 * Fires for the audio stream of a mirroring session as well as for audio-only
 * sessions; the Kotlin side keys the audio renderer's session state off it. */
static void cb_audio_running(void *cls, bool running) {
    int ct;
    pthread_mutex_lock(&g_audio_lock);
    ct = g_audio_ct;
    audio_decoder_release_locked();
    g_alac_errors = 0;
    if (running && ct == AUDIO_CT_ALAC) {
        /* AirPlay ALAC is always 16-bit stereo 44.1 kHz; the frame length comes from SETUP / fmtp */
        unsigned int fmtp[12] = { 96, g_audio_spf, 0, 16, 40, 10, 14, 2, 255, 0, 0, 44100 };
        g_alac = alac_decoder_create(fmtp);
        if (g_alac) {
            g_pcm_samples = (size_t) g_audio_spf * 2;
            g_pcm = malloc(g_pcm_samples * sizeof(int16_t));
            if (!g_pcm) audio_decoder_release_locked();
        }
        if (!g_alac) LOGE("ALAC decoder init failed (spf=%u); audio will be silent", g_audio_spf);
    }
    pthread_mutex_unlock(&g_audio_lock);
    LOGI("audio stream %s (ct=%d)", running ? "started" : "stopped", ct);

    JNIEnv *env = env_for_thread();
    if (!env) return;
    jobject listener = listener_snapshot(env);
    if (!listener) return;
    (*env)->CallVoidMethod(env, listener, g_on_audio_running, running ? JNI_TRUE : JNI_FALSE, (jint) ct);
    (*env)->DeleteLocalRef(env, listener);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
}
static void cb_register_client(void *cls, const char *id, const char *pk, const char *name) {}
static bool cb_check_register(void *cls, const char *pk) { return false; }
static const char *cb_passwd(void *cls, int *len) { return NULL; }
static void cb_export_dacp(void *cls, const char *a, const char *b) {}
static void cb_display_pin(void *cls, char *pin) {}
static void cb_on_video_play(void *cls, const char *location, const float pos) {}
static void cb_on_video_scrub(void *cls, const float pos) {}
static void cb_on_video_rate(void *cls, const float rate) {}
static void cb_on_video_stop(void *cls) {}
static void cb_on_video_info(void *cls, playback_info_t *info) {}
static float cb_on_playlist_remove(void *cls) { return 0.0f; }
static void cb_video_report_size(void *cls, float *a, float *b, float *c, float *d) {}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_vm = vm;
    if (pthread_key_create(&g_tls_key, tls_detach) != 0) return JNI_ERR;
    JNIEnv *env = env_for_thread();
    jclass cls = (*env)->FindClass(env, "io/github/pgodlews/wormhole/NativeBridge$Listener");
    if (!cls) return JNI_ERR;
    g_on_video_frame = (*env)->GetMethodID(env, cls, "onVideoFrame", "([BJJJZ)V");
    g_on_audio_frame = (*env)->GetMethodID(env, cls, "onAudioFrame", "([BJI)V");
    g_on_audio_volume = (*env)->GetMethodID(env, cls, "onAudioVolume", "(F)V");
    g_on_audio_flush = (*env)->GetMethodID(env, cls, "onAudioFlush", "()V");
    g_on_client_connected = (*env)->GetMethodID(env, cls, "onClientConnected", "(Ljava/lang/String;)V");
    g_on_client_disconnected = (*env)->GetMethodID(env, cls, "onClientDisconnected", "()V");
    g_on_mirror_running = (*env)->GetMethodID(env, cls, "onMirrorRunning", "(Z)V");
    g_on_audio_running = (*env)->GetMethodID(env, cls, "onAudioRunning", "(ZI)V");
    g_on_stream_error = (*env)->GetMethodID(env, cls, "onStreamError", "()V");
    return (g_on_video_frame && g_on_audio_frame && g_on_audio_volume && g_on_audio_flush
            && g_on_client_connected && g_on_client_disconnected
            && g_on_mirror_running && g_on_audio_running && g_on_stream_error) ? JNI_VERSION_1_6 : JNI_ERR;
}

JNIEXPORT void Java_io_github_pgodlews_wormhole_NativeBridge_nativeSetListener(JNIEnv *env, jclass cls, jobject listener) {
    pthread_mutex_lock(&g_listener_lock);
    if (g_listener) (*env)->DeleteGlobalRef(env, g_listener);
    g_listener = listener ? (*env)->NewGlobalRef(env, listener) : NULL;
    pthread_mutex_unlock(&g_listener_lock);
}

JNIEXPORT jint Java_io_github_pgodlews_wormhole_NativeBridge_nativeStart(JNIEnv *env, jclass cls,
                                                               jstring keyFile, jstring deviceId,
                                                               jstring serviceName,
                                                               jint width, jint height, jint maxFps, jboolean allowHevc) {
    pthread_mutex_lock(&g_lock);
    if (g_raop) {
        int port = raop_get_port(g_raop);
        pthread_mutex_unlock(&g_lock);
        return port;
    }

    raop_callbacks_t callbacks;
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.audio_process = cb_audio_process;
    callbacks.video_process = cb_video_process;
    callbacks.video_pause = cb_stub_void;
    callbacks.video_resume = cb_stub_void;
    callbacks.conn_feedback = cb_stub_void;
    callbacks.conn_reset = cb_conn_reset;
    callbacks.video_reset = cb_video_reset;
    callbacks.conn_init = cb_stub_void;
    callbacks.conn_destroy = cb_conn_destroy;
    callbacks.audio_flush = cb_audio_flush;
    callbacks.video_flush = cb_stub_flush;
    callbacks.audio_set_client_volume = cb_audio_get_volume;
    callbacks.audio_set_volume = cb_audio_set_volume;
    callbacks.audio_set_metadata = cb_audio_set_data;
    callbacks.audio_set_coverart = cb_audio_set_data;
    callbacks.audio_stop_coverart_rendering = cb_stub_void;
    callbacks.audio_remote_control_id = cb_audio_remote_control;
    callbacks.audio_set_progress = cb_audio_set_progress;
    callbacks.audio_get_format = cb_audio_get_format;
    callbacks.audio_running = cb_audio_running;
    callbacks.video_report_size = cb_video_report_size;
    callbacks.mirror_video_running = cb_mirror_video_running;
    callbacks.report_client_request = cb_report_client_request;
    callbacks.display_pin = cb_display_pin;
    callbacks.register_client = cb_register_client;
    callbacks.check_register = cb_check_register;
    callbacks.passwd = cb_passwd;
    callbacks.export_dacp = cb_export_dacp;
    callbacks.video_set_codec = cb_video_set_codec;
    callbacks.on_video_play = cb_on_video_play;
    callbacks.on_video_scrub = cb_on_video_scrub;
    callbacks.on_video_rate = cb_on_video_rate;
    callbacks.on_video_stop = cb_on_video_stop;
    callbacks.on_video_acquire_playback_info = cb_on_video_info;
    callbacks.on_video_playlist_remove = cb_on_playlist_remove;

    raop_t *raop = raop_init(&callbacks);
    if (!raop) { pthread_mutex_unlock(&g_lock); return -1; }
    raop_set_log_callback(raop, cb_log, NULL);
    raop_set_log_level(raop, LOGGER_INFO);

    const char *key = (*env)->GetStringUTFChars(env, keyFile, NULL);
    const char *id = (*env)->GetStringUTFChars(env, deviceId, NULL);
    int rc = raop_init2(raop, 0, id, key);
    (*env)->ReleaseStringUTFChars(env, keyFile, key);
    (*env)->ReleaseStringUTFChars(env, deviceId, id);
    if (rc < 0) { raop_destroy(raop); pthread_mutex_unlock(&g_lock); return -2; }

    /* Advertise the panel's native geometry so the client mirrors at full res. */
    raop_set_plist(raop, "width", width);
    raop_set_plist(raop, "height", height);
    raop_set_plist(raop, "refreshRate", maxFps);
    raop_set_plist(raop, "maxFPS", maxFps);

    /* /info asserts on raop->dnssd and reads its TXT records, so the dnssd
     * object must exist and be attached BEFORE the HTTP listener can accept
     * a request; a failure here is fatal, not a degraded startup. */
    const char *name = (*env)->GetStringUTFChars(env, serviceName, NULL);
    unsigned char hw[6] = {0};
    const char *hex = (*env)->GetStringUTFChars(env, deviceId, NULL);
    for (int i = 0; i < 6 && hex[2*i] && hex[2*i+1]; i++) {
        char byte[3] = { hex[2*i], hex[2*i+1], '\0' };
        hw[i] = (unsigned char) strtoul(byte, NULL, 16);
    }
    (*env)->ReleaseStringUTFChars(env, deviceId, hex);
    int dnssd_error = 0;
    dnssd_t *dnssd = dnssd_init(name, (int) strlen(name), (const char *) hw, 6, 0, &dnssd_error);
    (*env)->ReleaseStringUTFChars(env, serviceName, name);
    if (!dnssd || dnssd_error) {
        LOGE("dnssd init failed (%d); aborting startup", dnssd_error);
        dnssd_destroy(dnssd);
        raop_destroy(raop);
        pthread_mutex_unlock(&g_lock);
        return -4;
    }
    g_allow_hevc = allowHevc == JNI_TRUE;
    dnssd_set_airplay_features(dnssd, 42, g_allow_hevc ? 1 : 0);
    LOGI("HEVC advertisement: %s", g_allow_hevc ? "enabled" : "disabled");
    raop_set_dnssd(raop, dnssd);

    unsigned short tcp[2] = { 7100, 7000 };  /* mirror data, rtsp/httpd */
    unsigned short udp[3] = { 7011, 7001, 7101 };  /* timing, control, data */
    raop_set_tcp_ports(raop, tcp);
    raop_set_udp_ports(raop, udp);
    unsigned short port = 7000;
    /* Registration constructs TXT buffers read by /info. Finish BOTH before
     * starting the HTTP worker; attaching an empty dnssd object is insufficient.
     * The configured port is fixed. Roll back advertisement if binding fails. */
    int rc_raop = dnssd_register_raop(dnssd, port);
    int rc_airplay = dnssd_register_airplay(dnssd, port);
    if (rc_raop || rc_airplay) {
        LOGE("mDNS registration failed (raop=%d, airplay=%d); aborting startup", rc_raop, rc_airplay);
        dnssd_unregister_raop(dnssd);
        dnssd_unregister_airplay(dnssd);
        dnssd_destroy(dnssd);
        raop_destroy(raop);
        pthread_mutex_unlock(&g_lock);
        return -5;
    }
    rc = raop_start_httpd(raop, &port);
    if (rc < 0 || port != 7000) {
        raop_stop_httpd(raop);
        dnssd_unregister_raop(dnssd);
        dnssd_unregister_airplay(dnssd);
        dnssd_destroy(dnssd);
        raop_destroy(raop);
        pthread_mutex_unlock(&g_lock);
        return -3;
    }
    raop_set_port(raop, port);
    LOGI("mDNS responder advertising port %d", port);

    g_dnssd = dnssd;
    g_raop = raop;
    pthread_mutex_unlock(&g_lock);
    LOGI("Receiver listening on port %d (advertising %dx%d@%dHz, %s)", port, width, height, maxFps, width < height ? "portrait" : "landscape");
    return port;
}

JNIEXPORT jstring Java_io_github_pgodlews_wormhole_NativeBridge_nativePublicKey(JNIEnv *env, jclass cls) {
    pthread_mutex_lock(&g_lock);
    jstring result = g_raop ? (*env)->NewStringUTF(env, raop_get_pk_str(g_raop)) : NULL;
    pthread_mutex_unlock(&g_lock);
    return result;
}

JNIEXPORT void Java_io_github_pgodlews_wormhole_NativeBridge_nativeStop(JNIEnv *env, jclass cls) {
    pthread_mutex_lock(&g_lock);
    /* Stop and join HTTP first: in-flight /info handlers read TXT records from
     * the dnssd object, so it must stay valid until no connection can run.
     * (Joining can fire conn_destroy callbacks; they take g_listener_lock,
     * never g_lock, so holding g_lock here cannot deadlock.) */
    if (g_raop) raop_stop_httpd(g_raop);
    if (g_dnssd) {
        dnssd_unregister_raop(g_dnssd);
        dnssd_unregister_airplay(g_dnssd);
        dnssd_destroy(g_dnssd);
        g_dnssd = NULL;
    }
    if (g_raop) {
        raop_destroy(g_raop);  /* owns the pk string dnssd pointed at; freed last */
        g_raop = NULL;
    }
    pthread_mutex_lock(&g_audio_lock);
    audio_decoder_release_locked();   /* no RTP thread can call cb_audio_process past raop_destroy */
    pthread_mutex_unlock(&g_audio_lock);
    pthread_mutex_unlock(&g_lock);
}
