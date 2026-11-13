# Stdlib modules that failed to import because of runtime/compiler gaps (stdlib status pipeline findings).
import calendar
import email.mime.text
import email.policy
import tarfile
import _strptime
import imaplib
import pprint
import uuid
import zoneinfo

msg = email.mime.text.MIMEText("hello", "plain")
print(msg["Content-Type"])
print(email.policy.default.clone(linesep="\n").linesep == "\n")
print(calendar.month_name[1], calendar.MONDAY, calendar.JANUARY)
print(tarfile.TarInfo.__slots__ is not None)
print(imaplib.Int2AP(1234), imaplib.IMAP4.__name__)
pprint.pprint({"b": [1, 2, 3], "a": {"x": (1, 2)}})
print(uuid.SafeUUID.safe.name)
print(zoneinfo.ZoneInfo.__name__)
