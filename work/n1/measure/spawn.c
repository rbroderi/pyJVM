#define _GNU_SOURCE
#include <spawn.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <poll.h>
#include <unistd.h>
extern char **environ;
static int run(const char *label, const char *path, char *const argv[], posix_spawn_file_actions_t *fa, posix_spawnattr_t *at, int p){
  pid_t pid = -12345; int st = 0;
  int rc = p ? posix_spawnp(&pid, path, fa, at, argv, environ) : posix_spawn(&pid, path, fa, at, argv, environ);
  printf("%s: rc=%d (%s) pid_set=%d", label, rc, rc ? strerror(rc) : "ok", pid != -12345);
  if (rc == 0) { pid_t w = waitpid(pid, &st, 0); printf(" wait_ok=%d status=0x%x", w == pid, st); }
  else { pid_t w = waitpid(-1, &st, WNOHANG); printf(" waitpid(-1,WNOHANG)=%d errno=%d", (int)w, errno); }
  printf("\n"); fflush(stdout); return rc;
}
int main(void){
  char *sh[] = {"sh", "-c", "pwd; echo fds:; ls /proc/self/fd | tr '\\n' ' '; echo", NULL};
  char *a1[] = {"x", NULL};
  mkdir("/tmp/n1sp", 0755); mkdir("/tmp/n1sp/sub", 0755); mkdir("/tmp/n1sp/noperm", 0000);
  int f = open("/tmp/n1sp/noexec", O_CREAT|O_WRONLY|O_TRUNC, 0644); write(f, "#!/bin/sh\necho hi\n", 18); close(f);
  f = open("/tmp/n1sp/noshebang", O_CREAT|O_WRONLY|O_TRUNC, 0755); write(f, "echo noshebang ran\n", 19); close(f);
  f = open("/tmp/n1sp/sub/prog", O_CREAT|O_WRONLY|O_TRUNC, 0755); write(f, "#!/bin/sh\necho prog in sub ran; pwd\n", 36); close(f);
  f = open("/tmp/n1sp/badinterp", O_CREAT|O_WRONLY|O_TRUNC, 0755); write(f, "#!/no/such/interp\n", 18); close(f);
  run("missing", "/no/such/prog", a1, NULL, NULL, 0);
  run("missing-p", "no-such-prog-xyz", a1, NULL, NULL, 1);
  run("noexec", "/tmp/n1sp/noexec", a1, NULL, NULL, 0);
  run("isdir", "/tmp/n1sp", a1, NULL, NULL, 0);
  run("notdir", "/tmp/n1sp/noexec/x", a1, NULL, NULL, 0);
  run("noshebang", "/tmp/n1sp/noshebang", a1, NULL, NULL, 0);
  run("noshebang-p", "/tmp/n1sp/noshebang", a1, NULL, NULL, 1);
  run("badinterp", "/tmp/n1sp/badinterp", a1, NULL, NULL, 0);
  run("empty", "", a1, NULL, NULL, 0);
  posix_spawn_file_actions_t fa; int r;
  posix_spawn_file_actions_init(&fa); r = posix_spawn_file_actions_addchdir_np(&fa, "/tmp/n1sp/sub"); printf("addchdir rc=%d\n", r);
  run("chdir+abs sh", "/bin/sh", sh, &fa, NULL, 0);
  char *pr[] = {"prog", NULL};
  run("chdir+relative ./prog", "./prog", pr, &fa, NULL, 0);
  run("chdir+relative ./prog (p)", "./prog", pr, &fa, NULL, 1);
  posix_spawn_file_actions_destroy(&fa);
  posix_spawn_file_actions_init(&fa); posix_spawn_file_actions_addchdir_np(&fa, "/tmp/n1sp"); posix_spawn_file_actions_addchdir_np(&fa, "sub");
  run("two chdirs (abs then relative)", "/bin/sh", sh, &fa, NULL, 0);
  posix_spawn_file_actions_destroy(&fa);
  const char *bad[] = {"/no/such/dir", "/tmp/n1sp/noexec", "/tmp/n1sp/noperm", ""};
  for (int i = 0; i < 4; i++) { posix_spawn_file_actions_init(&fa); posix_spawn_file_actions_addchdir_np(&fa, bad[i]); char lab[100]; snprintf(lab, 100, "chdir bad '%s'", bad[i]); run(lab, "/bin/sh", sh, &fa, NULL, 0); posix_spawn_file_actions_destroy(&fa);
     struct stat sb; int sr = stat(bad[i], &sb); int se = errno; int ar = faccessat(AT_FDCWD, bad[i], X_OK, AT_EACCESS); int ae = errno;
     printf("   parent check: stat=%d errno=%d isdir=%d; faccessat X_OK=%d errno=%d\n", sr, sr ? se : 0, sr == 0 && S_ISDIR(sb.st_mode), ar, ar ? ae : 0); }
  /* dup2(fd,fd) clears cloexec; closefrom */
  int p[2]; pipe2(p, O_CLOEXEC); int q[2]; pipe2(q, O_CLOEXEC);
  printf("pipes %d %d %d %d\n", p[0], p[1], q[0], q[1]);
  posix_spawn_file_actions_init(&fa); r = posix_spawn_file_actions_adddup2(&fa, p[1], p[1]); printf("adddup2 same rc=%d\n", r);
  run("dup2(fd,fd) keeps it (cloexec others closed)", "/bin/sh", sh, &fa, NULL, 0);
  posix_spawn_file_actions_destroy(&fa);
  fcntl(q[0], F_SETFD, 0); fcntl(q[1], F_SETFD, 0);
  run("no actions, q inheritable", "/bin/sh", sh, NULL, NULL, 0);
  posix_spawn_file_actions_init(&fa); r = posix_spawn_file_actions_addclosefrom_np(&fa, 3); printf("addclosefrom rc=%d\n", r);
  run("closefrom 3", "/bin/sh", sh, &fa, NULL, 0);
  posix_spawn_file_actions_destroy(&fa);
  posix_spawn_file_actions_init(&fa); posix_spawn_file_actions_adddup2(&fa, q[1], q[1]); posix_spawn_file_actions_addclosefrom_np(&fa, 3);
  run("dup2(q1,q1) then closefrom 3", "/bin/sh", sh, &fa, NULL, 0);
  posix_spawn_file_actions_destroy(&fa);
  posix_spawn_file_actions_init(&fa); posix_spawn_file_actions_addclose(&fa, 9999);
  run("addclose(9999) not open", "/bin/sh", sh, &fa, NULL, 0);
  posix_spawn_file_actions_destroy(&fa);
  posix_spawn_file_actions_init(&fa); posix_spawn_file_actions_adddup2(&fa, 9999, 1);
  run("adddup2(9999,1) bad fd", "/bin/sh", sh, &fa, NULL, 0);
  posix_spawn_file_actions_destroy(&fa);
  posix_spawn_file_actions_init(&fa); r = posix_spawn_file_actions_addopen(&fa, 1, "/no/such/dir/file", O_WRONLY|O_CREAT, 0644);
  run("addopen bad path", "/bin/sh", sh, &fa, NULL, 0);
  posix_spawn_file_actions_destroy(&fa);
  /* attrs */
  char *ids[] = {"sh", "-c", "echo sid_eq_pid=$(( $(cut -d' ' -f6 /proc/self/stat) == $$ )) pgrp_eq_pid=$(( $(cut -d' ' -f5 /proc/self/stat) == $$ )); grep -E 'Sig(Blk|Ign|Cgt)' /proc/$$/status | tr '\\n\\t' '  '; echo", NULL};
  posix_spawnattr_t at;
  run("no attr", "/bin/sh", ids, NULL, NULL, 0);
  posix_spawnattr_init(&at); posix_spawnattr_setflags(&at, POSIX_SPAWN_SETSID); run("SETSID", "/bin/sh", ids, NULL, &at, 0); posix_spawnattr_destroy(&at);
  posix_spawnattr_init(&at); posix_spawnattr_setflags(&at, POSIX_SPAWN_SETPGROUP); posix_spawnattr_setpgroup(&at, 0); run("SETPGROUP 0", "/bin/sh", ids, NULL, &at, 0); posix_spawnattr_destroy(&at);
  posix_spawnattr_init(&at); posix_spawnattr_setflags(&at, POSIX_SPAWN_SETPGROUP); posix_spawnattr_setpgroup(&at, 1); run("SETPGROUP 1 (EPERM expected)", "/bin/sh", ids, NULL, &at, 0); posix_spawnattr_destroy(&at);
  sigset_t m; sigemptyset(&m); sigaddset(&m, SIGUSR1); sigaddset(&m, SIGQUIT); sigprocmask(SIG_BLOCK, &m, NULL); signal(SIGPIPE, SIG_IGN); signal(SIGUSR2, SIG_IGN);
  run("parent blocks USR1+QUIT, ignores PIPE+USR2; no attr", "/bin/sh", ids, NULL, NULL, 0);
  sigset_t e; sigemptyset(&e); sigset_t d; sigemptyset(&d); sigaddset(&d, SIGPIPE); sigaddset(&d, SIGXFSZ);
  posix_spawnattr_init(&at); posix_spawnattr_setflags(&at, POSIX_SPAWN_SETSIGMASK|POSIX_SPAWN_SETSIGDEF); posix_spawnattr_setsigmask(&at, &e); posix_spawnattr_setsigdefault(&at, &d);
  run("SETSIGMASK empty + SETSIGDEF pipe,xfsz", "/bin/sh", ids, NULL, &at, 0); posix_spawnattr_destroy(&at);
  /* sigset layout */
  sigemptyset(&m); sigaddset(&m, 1); sigaddset(&m, 13); sigaddset(&m, 64); unsigned long *w = (unsigned long *)&m; printf("sigset{1,13,64} word0=0x%lx word1=0x%lx\n", w[0], w[1]);
  posix_spawnattr_init(&at); posix_spawnattr_setflags(&at, 0x8e); posix_spawnattr_setpgroup(&at, 0x1234567); sigemptyset(&d); sigaddset(&d, 13); posix_spawnattr_setsigdefault(&at, &d); sigemptyset(&e); sigaddset(&e, 3); posix_spawnattr_setsigmask(&at, &e);
  unsigned char *b = (unsigned char *)&at; printf("attr bytes: flags@0=%02x%02x pgrp@4=%02x%02x%02x%02x sd@8=%02x%02x ss@136=%02x\n", b[1], b[0], b[7], b[6], b[5], b[4], b[9], b[8], b[136]);
  int st; printf("waitpid(getpid)=%d errno=%d\n", (int)waitpid(getpid(), &st, 0), errno); printf("waitpid(1)=%d errno=%d\n", (int)waitpid(1, &st, 0), errno);
  pid_t pid; char *sl[] = {"sleep", "0.3", NULL}; posix_spawn(&pid, "/bin/sleep", NULL, NULL, sl, environ);
  int pfd = (int)syscall(SYS_pidfd_open, pid, 0); struct pollfd pf = {pfd, POLLIN, 0};
  int n0 = poll(&pf, 1, 50); int n1 = poll(&pf, 1, 2000); printf("pidfd>2:%d poll50=%d poll2000=%d revents=%d cloexec=%d\n", pfd > 2, n0, n1, pf.revents, fcntl(pfd, F_GETFD));
  siginfo_t si; memset(&si, 0, sizeof si); r = waitid(P_PID, pid, &si, WEXITED|WNOWAIT); printf("waitid NOWAIT r=%d signo=%d code=%d status=%d pid_ok=%d\n", r, si.si_signo, si.si_code, si.si_status, si.si_pid == pid);
  printf("waitpid after=%d st=0x%x\n", waitpid(pid, &st, 0) == pid, st); close(pfd);
  printf("pidfd_open(dead)=%ld errno=%d\n", syscall(SYS_pidfd_open, pid, 0), errno);
  char **before = environ; setenv("N1_NEWVAR_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "1", 1); printf("environ pointer changed after setenv of new var: %d\n", before != environ);
  chmod("/tmp/n1sp/noperm", 0755);
  return 0;
}
