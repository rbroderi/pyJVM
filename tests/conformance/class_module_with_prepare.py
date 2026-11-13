# A class built through a custom __prepare__ mapping still gets __module__ from the defining module.
import calendar
import enum


class Namespace(dict):
    def __setitem__(self, key, value):
        super().__setitem__(key, value)


class Meta(type):
    @classmethod
    def __prepare__(mcs, name, bases):
        return Namespace()


class Plain(metaclass=Meta):
    pass


class Color(enum.IntEnum):
    RED = 1


print(Plain.__module__, Color.__module__)
print(calendar.Day.__module__, calendar.Month.__module__)
print(int(calendar.MONDAY), int(calendar.JANUARY), int(calendar.SUNDAY))
