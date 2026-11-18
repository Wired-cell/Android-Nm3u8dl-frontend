#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static volatile sig_atomic_t child_pid = -1;

static void forward_signal(int sig) {
    pid_t pid = (pid_t)child_pid;
    if (pid > 0) kill(-pid, sig);
}

int main(int argc, char **argv) {
    if (argc < 2) {
        dprintf(STDERR_FILENO, "pty-runner: missing command\n");
        return 125;
    }

    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0 || grantpt(master) != 0 || unlockpt(master) != 0) {
        dprintf(STDERR_FILENO, "pty-runner: cannot create PTY: %s\n", strerror(errno));
        return 126;
    }
    char *slave_name = ptsname(master);
    if (!slave_name) {
        dprintf(STDERR_FILENO, "pty-runner: ptsname failed: %s\n", strerror(errno));
        close(master);
        return 126;
    }

    struct winsize ws = { .ws_row = 40, .ws_col = 160, .ws_xpixel = 0, .ws_ypixel = 0 };
    (void)ioctl(master, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid < 0) {
        dprintf(STDERR_FILENO, "pty-runner: fork failed: %s\n", strerror(errno));
        close(master);
        return 126;
    }
    if (pid == 0) {
        close(master);
        if (setsid() < 0) _exit(126);
        int slave = open(slave_name, O_RDWR);
        if (slave < 0) _exit(126);
        (void)ioctl(slave, TIOCSCTTY, 0);
        (void)ioctl(slave, TIOCSWINSZ, &ws);
        if (dup2(slave, STDIN_FILENO) < 0 ||
            dup2(slave, STDOUT_FILENO) < 0 ||
            dup2(slave, STDERR_FILENO) < 0) _exit(126);
        if (slave > STDERR_FILENO) close(slave);
        setenv("TERM", "xterm-256color", 1);
        setenv("CLICOLOR_FORCE", "1", 1);
        execvp(argv[1], &argv[1]);
        dprintf(STDERR_FILENO, "pty-runner: exec %s failed: %s\n", argv[1], strerror(errno));
        _exit(127);
    }

    child_pid = (sig_atomic_t)pid;
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = forward_signal;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);

    char buffer[8192];
    for (;;) {
        struct pollfd pfd = { .fd = master, .events = POLLIN | POLLHUP };
        int ready = poll(&pfd, 1, 250);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0) break;
        if (ready > 0 && (pfd.revents & (POLLIN | POLLHUP))) {
            ssize_t n = read(master, buffer, sizeof(buffer));
            if (n > 0) {
                ssize_t off = 0;
                while (off < n) {
                    ssize_t w = write(STDOUT_FILENO, buffer + off, (size_t)(n - off));
                    if (w < 0 && errno == EINTR) continue;
                    if (w <= 0) break;
                    off += w;
                }
            } else if (n == 0 || (n < 0 && errno == EIO)) {
                break;
            } else if (errno != EINTR && errno != EAGAIN) {
                break;
            }
        }
        int status;
        pid_t done = waitpid(pid, &status, WNOHANG);
        if (done == pid) {
            child_pid = -1;
            close(master);
            if (WIFEXITED(status)) return WEXITSTATUS(status);
            if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
            return 125;
        }
    }

    int status = 0;
    (void)kill(-pid, SIGTERM);
    (void)waitpid(pid, &status, 0);
    child_pid = -1;
    close(master);
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 125;
}
