#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pty.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#ifndef NSIG
#define NSIG 64
#endif

typedef struct {
    int master_fd;
    pid_t pid;
} PtySession;

static PtySession *session_from_handle(jlong handle) {
    return (PtySession *)(intptr_t)handle;
}

static char *copy_string(JNIEnv *env, jstring value) {
    if (value == NULL) return NULL;
    const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
    if (utf == NULL) return NULL;
    char *copy = strdup(utf);
    (*env)->ReleaseStringUTFChars(env, value, utf);
    return copy;
}

static char **copy_string_array(JNIEnv *env, jobjectArray values, int extra, const char *first) {
    jsize count = values == NULL ? 0 : (*env)->GetArrayLength(env, values);
    char **result = calloc((size_t)count + (size_t)extra + 1, sizeof(char *));
    if (result == NULL) return NULL;
    if (first != NULL) {
        result[0] = strdup(first);
        if (result[0] == NULL) goto error;
    }
    for (jsize i = 0; i < count; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, values, i);
        result[i + extra - 1] = copy_string(env, value);
        (*env)->DeleteLocalRef(env, value);
        if (result[i + extra - 1] == NULL) goto error;
    }
    return result;
error:
    for (size_t i = 0; i < (size_t)count + (size_t)extra; i++) free(result[i]);
    free(result);
    return NULL;
}

static void free_string_array(char **values) {
    if (values == NULL) return;
    for (char **item = values; *item != NULL; item++) free(*item);
    free(values);
}

JNIEXPORT jlong JNICALL
Java_com_opendroid_ai_core_pty_PtyNative_nativeStart(
        JNIEnv *env, jclass clazz, jstring executable, jobjectArray arguments,
        jstring working_directory, jobjectArray environment) {
    (void)clazz;
    char *exe = copy_string(env, executable);
    char *cwd = copy_string(env, working_directory);
    if (exe == NULL || cwd == NULL) {
        free(exe);
        free(cwd);
        return 0;
    }

    jsize argument_count = arguments == NULL ? 0 : (*env)->GetArrayLength(env, arguments);
    char **argv = copy_string_array(env, arguments, 2, exe);
    char **envp = copy_string_array(env, environment, 1, NULL);
    if (argv == NULL || envp == NULL) {
        free(exe);
        free(cwd);
        free_string_array(argv);
        free_string_array(envp);
        return 0;
    }
    argv[argument_count + 1] = NULL;

    int master_fd = -1;
    pid_t pid = forkpty(&master_fd, NULL, NULL, NULL);
    if (pid < 0) {
        free(exe);
        free(cwd);
        free_string_array(argv);
        free_string_array(envp);
        return 0;
    }
    if (pid == 0) {
        if (chdir(cwd) != 0) _exit(126);
        execve(exe, argv, envp);
        _exit(errno == ENOENT ? 127 : 126);
    }

    int flags = fcntl(master_fd, F_GETFL, 0);
    if (flags >= 0) fcntl(master_fd, F_SETFL, flags | O_NONBLOCK);
    PtySession *session = calloc(1, sizeof(PtySession));
    if (session == NULL) {
        kill(pid, SIGTERM);
        close(master_fd);
        waitpid(pid, NULL, 0);
    } else {
        session->master_fd = master_fd;
        session->pid = pid;
    }
    free(exe);
    free(cwd);
    free_string_array(argv);
    free_string_array(envp);
    return (jlong)(intptr_t)session;
}

JNIEXPORT jint JNICALL
Java_com_opendroid_ai_core_pty_PtyNative_nativeRead(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray destination, jint timeout_ms) {
    (void)clazz;
    PtySession *session = session_from_handle(handle);
    if (session == NULL || session->master_fd < 0 || destination == NULL) return -1;
    jsize length = (*env)->GetArrayLength(env, destination);
    if (length <= 0) return 0;
    struct pollfd descriptor = {.fd = session->master_fd, .events = POLLIN | POLLHUP};
    int ready;
    do {
        ready = poll(&descriptor, 1, timeout_ms);
    } while (ready < 0 && errno == EINTR);
    if (ready == 0) return 0;
    jbyte buffer[8192];
    ssize_t count;
    do {
        count = read(session->master_fd, buffer, (size_t)(length < (jint)sizeof(buffer) ? length : (jint)sizeof(buffer)));
    } while (count < 0 && errno == EINTR);
    if (count > 0) (*env)->SetByteArrayRegion(env, destination, 0, (jsize)count, buffer);
    if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return 0;
    if (count < 0 && errno == EIO) return -1;
    return (jint)count;
}

JNIEXPORT jint JNICALL
Java_com_opendroid_ai_core_pty_PtyNative_nativeWrite(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray source) {
    (void)clazz;
    PtySession *session = session_from_handle(handle);
    if (session == NULL || session->master_fd < 0 || source == NULL) return -1;
    jsize length = (*env)->GetArrayLength(env, source);
    jbyte buffer[8192];
    jsize offset = 0;
    while (offset < length) {
        jsize chunk = length - offset;
        if (chunk > (jsize)sizeof(buffer)) chunk = (jsize)sizeof(buffer);
        (*env)->GetByteArrayRegion(env, source, offset, chunk, buffer);
        ssize_t written = write(session->master_fd, buffer, (size_t)chunk);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return offset == 0 ? -1 : offset;
        offset += (jsize)written;
    }
    return offset;
}

JNIEXPORT jboolean JNICALL
Java_com_opendroid_ai_core_pty_PtyNative_nativeResize(
        JNIEnv *env, jclass clazz, jlong handle, jint rows, jint columns) {
    (void)env;
    (void)clazz;
    PtySession *session = session_from_handle(handle);
    if (session == NULL || session->master_fd < 0) return JNI_FALSE;
    struct winsize size = {.ws_row = (unsigned short)rows, .ws_col = (unsigned short)columns};
    return ioctl(session->master_fd, TIOCSWINSZ, &size) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_opendroid_ai_core_pty_PtyNative_nativeExitCode(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    PtySession *session = session_from_handle(handle);
    if (session == NULL) return 0;
    int status = 0;
    pid_t result = waitpid(session->pid, &status, WNOHANG);
    if (result == 0) return -1;
    if (result < 0) return errno == ECHILD ? 0 : -2;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -2;
}

JNIEXPORT void JNICALL
Java_com_opendroid_ai_core_pty_PtyNative_nativeClose(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    PtySession *session = session_from_handle(handle);
    if (session == NULL) return;
    int exit_code = Java_com_opendroid_ai_core_pty_PtyNative_nativeExitCode(env, clazz, handle);
    if (exit_code == -1) {
        kill(session->pid, SIGHUP);
        kill(session->pid, SIGTERM);
        for (int attempt = 0; attempt < 100; attempt++) {
            int status = 0;
            pid_t result = waitpid(session->pid, &status, WNOHANG);
            if (result == session->pid || (result < 0 && errno == ECHILD)) break;
            usleep(20 * 1000);
        }
        if (waitpid(session->pid, NULL, WNOHANG) == 0) {
            kill(session->pid, SIGKILL);
            waitpid(session->pid, NULL, 0);
        }
    }
    if (session->master_fd >= 0) close(session->master_fd);
    free(session);
}
