# time.struct_time, gmtime, asctime, strftime and strptime (calendar conversion).
import time

t = time.gmtime(0)
print(t)
print(tuple(t), t.tm_year, t.tm_zone, t.tm_gmtoff, len(t), t[0], t[-1])
u = time.gmtime(1700000000)
print(u, time.asctime(u))
print(time.strftime("%Y-%m-%d %H:%M:%S %a %A %b %B %j %U %W %w %p %y %I %e %% %c %x %X", u))
print(time.struct_time((2020, 1, 2, 3, 4, 5, 3, 2, 0)))
print(time.struct_time((2020, 1, 2, 3, 4, 5, 3, 2, 0)) == (2020, 1, 2, 3, 4, 5, 3, 2, 0))
print(time.mktime(time.localtime(1700000000)) == 1700000000.0)
print(time.strptime("2020-02-03 04:05:06", "%Y-%m-%d %H:%M:%S"))
print(time.gmtime(-1).tm_year, time.gmtime(86400 * 365.25 * 100).tm_year)
try:
    time.struct_time((1, 2, 3))
except TypeError as exc:
    print("TypeError")
