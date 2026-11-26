/* N2 struct layouts and a few constants, Linux x86-64 glibc. Read-only: changes nothing. */
#define _GNU_SOURCE
#include <stdio.h>
#include <stddef.h>
#include <fcntl.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/file.h>
#include <pwd.h>
#include <grp.h>
#include <unistd.h>
#include <stdlib.h>
#define O(s, f) printf("  %s.%s offset %zu size %zu\n", #s, #f, offsetof(struct s, f), sizeof(((struct s *)0)->f))
int main(void) {
    printf("struct flock size %zu\n", sizeof(struct flock));
    O(flock, l_type); O(flock, l_whence); O(flock, l_start); O(flock, l_len); O(flock, l_pid);
    printf("struct termios size %zu NCCS %d\n", sizeof(struct termios), NCCS);
    O(termios, c_iflag); O(termios, c_oflag); O(termios, c_cflag); O(termios, c_lflag); O(termios, c_line); O(termios, c_cc); O(termios, c_ispeed); O(termios, c_ospeed);
    printf("struct winsize size %zu\n", sizeof(struct winsize));
    O(winsize, ws_row); O(winsize, ws_col); O(winsize, ws_xpixel); O(winsize, ws_ypixel);
    printf("struct rlimit size %zu (rlim_t %zu)\n", sizeof(struct rlimit), sizeof(rlim_t));
    O(rlimit, rlim_cur); O(rlimit, rlim_max);
    printf("RLIM_INFINITY %llu RLIM_NLIMITS %d\n", (unsigned long long) RLIM_INFINITY, (int) RLIM_NLIMITS);
    printf("struct rusage size %zu\n", sizeof(struct rusage));
    printf("struct passwd size %zu\n", sizeof(struct passwd));
    O(passwd, pw_name); O(passwd, pw_passwd); O(passwd, pw_uid); O(passwd, pw_gid); O(passwd, pw_gecos); O(passwd, pw_dir); O(passwd, pw_shell);
    printf("struct group size %zu\n", sizeof(struct group));
    O(group, gr_name); O(group, gr_passwd); O(group, gr_gid); O(group, gr_mem);
    printf("_SC_GETPW_R_SIZE_MAX %d -> %ld; _SC_GETGR_R_SIZE_MAX %d -> %ld; _SC_PAGESIZE %d -> %ld\n", _SC_GETPW_R_SIZE_MAX, sysconf(_SC_GETPW_R_SIZE_MAX), _SC_GETGR_R_SIZE_MAX, sysconf(_SC_GETGR_R_SIZE_MAX), _SC_PAGESIZE, sysconf(_SC_PAGESIZE));
    printf("F_GETLK %d F_SETLK %d F_SETLKW %d F_OFD_GETLK %d F_OFD_SETLK %d F_OFD_SETLKW %d F_DUPFD_CLOEXEC %d F_GETPIPE_SZ %d\n", F_GETLK, F_SETLK, F_SETLKW, F_OFD_GETLK, F_OFD_SETLK, F_OFD_SETLKW, F_DUPFD_CLOEXEC, F_GETPIPE_SZ);
    printf("F_RDLCK %d F_WRLCK %d F_UNLCK %d LOCK_SH %d LOCK_EX %d LOCK_NB %d LOCK_UN %d\n", F_RDLCK, F_WRLCK, F_UNLCK, LOCK_SH, LOCK_EX, LOCK_NB, LOCK_UN);
    printf("TCGETS 0x%lx TCSETS 0x%lx TIOCGWINSZ 0x%lx TIOCSWINSZ 0x%lx FIONREAD 0x%lx TIOCSCTTY 0x%lx\n", (unsigned long) TCGETS, (unsigned long) TCSETS, (unsigned long) TIOCGWINSZ, (unsigned long) TIOCSWINSZ, (unsigned long) FIONREAD, (unsigned long) TIOCSCTTY);
    printf("TCSANOW %d TCSADRAIN %d TCSAFLUSH %d VMIN %d VTIME %d ICANON %d ECHO %d B38400 %d\n", TCSANOW, TCSADRAIN, TCSAFLUSH, VMIN, VTIME, ICANON, ECHO, B38400);
    printf("RUSAGE_SELF %d RUSAGE_CHILDREN %d RUSAGE_THREAD %d\n", RUSAGE_SELF, RUSAGE_CHILDREN, RUSAGE_THREAD);
    printf("O_RDWR %d O_NOCTTY %d O_CLOEXEC %d\n", O_RDWR, O_NOCTTY, O_CLOEXEC);
    return 0;
}
