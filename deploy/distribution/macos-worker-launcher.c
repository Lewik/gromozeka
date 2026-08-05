#include <ApplicationServices/ApplicationServices.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static int request_computer_use_permissions(void) {
    bool screen_capture = CGRequestScreenCaptureAccess();
    const void *keys[] = {kAXTrustedCheckOptionPrompt};
    const void *values[] = {kCFBooleanTrue};
    CFDictionaryRef options = CFDictionaryCreate(
        kCFAllocatorDefault,
        keys,
        values,
        1,
        &kCFTypeDictionaryKeyCallBacks,
        &kCFTypeDictionaryValueCallBacks
    );
    bool accessibility = AXIsProcessTrustedWithOptions(options);
    CFRelease(options);
    printf(
        "Screen Recording: %s\nAccessibility: %s\n",
        screen_capture ? "granted" : "not granted",
        accessibility ? "granted" : "not granted"
    );
    return 0;
}

static int print_computer_use_permissions(void) {
    printf(
        "{\"screenRecording\":%s,\"accessibility\":%s}\n",
        CGPreflightScreenCaptureAccess() ? "true" : "false",
        AXIsProcessTrusted() ? "true" : "false"
    );
    return 0;
}

int main(int argc, char *argv[]) {
    if (argc < 2) {
        fprintf(
            stderr,
            "Usage: %s <worker-launcher> [arguments...] | --request-computer-use-permissions | --computer-use-permissions-status\n",
            argv[0]
        );
        return 2;
    }

    if (argc == 2 && strcmp(argv[1], "--request-computer-use-permissions") == 0) {
        return request_computer_use_permissions();
    }
    if (argc == 2 && strcmp(argv[1], "--computer-use-permissions-status") == 0) {
        return print_computer_use_permissions();
    }

    execv(argv[1], &argv[1]);
    int error = errno;
    perror("Failed to start Gromozeka Worker");
    return error == ENOENT ? 127 : 126;
}
