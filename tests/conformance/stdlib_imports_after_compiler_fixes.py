# Stdlib modules that failed to import because of runtime/compiler gaps (stdlib status pipeline findings).
import calendar
import email.mime.text
import email.policy
import tarfile
import _strptime
import uuid

msg = email.mime.text.MIMEText("hello", "plain")
print(msg["Content-Type"])
print(email.policy.default.clone(linesep="\n").linesep == "\n")
print(calendar.month_name[1], int(calendar.MONDAY), int(calendar.JANUARY))
print(tarfile.TarInfo.__slots__ is not None)
print(uuid.SafeUUID.safe.name)
