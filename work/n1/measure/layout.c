#define _GNU_SOURCE
#include <spawn.h>
#include <signal.h>
#include <stdio.h>
#include <stddef.h>
#include <sys/wait.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <sched.h>
#include <gnu/libc-version.h>
#define P(x) printf(#x " = %ld\n", (long)(x))
int main(void){
  printf("glibc %s\n", gnu_get_libc_version());
  P(sizeof(posix_spawn_file_actions_t)); P(sizeof(posix_spawnattr_t)); P(sizeof(sigset_t)); P(sizeof(siginfo_t)); P(sizeof(struct rusage)); P(sizeof(struct sched_param)); P(sizeof(pid_t)); P(sizeof(id_t)); P(sizeof(idtype_t));
  P(POSIX_SPAWN_RESETIDS); P(POSIX_SPAWN_SETPGROUP); P(POSIX_SPAWN_SETSIGDEF); P(POSIX_SPAWN_SETSIGMASK); P(POSIX_SPAWN_SETSCHEDPARAM); P(POSIX_SPAWN_SETSCHEDULER); P(POSIX_SPAWN_USEVFORK); P(POSIX_SPAWN_SETSID);
#ifdef POSIX_SPAWN_SETCGROUP
  P(POSIX_SPAWN_SETCGROUP);
#endif
  P(offsetof(siginfo_t, si_signo)); P(offsetof(siginfo_t, si_errno)); P(offsetof(siginfo_t, si_code)); P(offsetof(siginfo_t, si_pid)); P(offsetof(siginfo_t, si_uid)); P(offsetof(siginfo_t, si_status));
  P(offsetof(struct rusage, ru_utime)); P(offsetof(struct rusage, ru_stime)); P(offsetof(struct rusage, ru_maxrss)); P(offsetof(struct rusage, ru_ixrss)); P(offsetof(struct rusage, ru_idrss)); P(offsetof(struct rusage, ru_isrss)); P(offsetof(struct rusage, ru_minflt)); P(offsetof(struct rusage, ru_majflt)); P(offsetof(struct rusage, ru_nswap)); P(offsetof(struct rusage, ru_inblock)); P(offsetof(struct rusage, ru_oublock)); P(offsetof(struct rusage, ru_msgsnd)); P(offsetof(struct rusage, ru_msgrcv)); P(offsetof(struct rusage, ru_nsignals)); P(offsetof(struct rusage, ru_nvcsw)); P(offsetof(struct rusage, ru_nivcsw));
  P(WNOHANG); P(WUNTRACED); P(WSTOPPED); P(WEXITED); P(WCONTINUED); P(WNOWAIT); P(__WALL); P(__WCLONE); P(__WNOTHREAD);
  P(P_ALL); P(P_PID); P(P_PGID); P(P_PIDFD);
  P(CLD_EXITED); P(CLD_KILLED); P(CLD_DUMPED); P(CLD_TRAPPED); P(CLD_STOPPED); P(CLD_CONTINUED);
  P(SYS_pidfd_open); P(SYS_close_range); P(SIGCHLD); P(SIGPIPE); P(SIGXFSZ); P(_NSIG); P(SIGRTMIN); P(SIGRTMAX);
  P(_SC_PAGESIZE); P(_SC_OPEN_MAX); P(_SC_CLK_TCK); P(_SC_NPROCESSORS_ONLN); P(_SC_ARG_MAX); P(_CS_PATH); P(_CS_GNU_LIBC_VERSION); P(_CS_GNU_LIBPTHREAD_VERSION);
  P(SCHED_OTHER); P(SCHED_FIFO); P(SCHED_RR); P(SCHED_BATCH); P(SCHED_IDLE); P(SCHED_RESET_ON_FORK);
  P(PRIO_PROCESS);
  return 0;
}
