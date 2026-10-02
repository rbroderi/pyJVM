"""Bounded benchmark kernels; imported by CPython for checksum verification only."""


def arithmetic(n):
    i = 0
    total = 0
    while i < n:
        total = total + (i * 3 + 7)
        i = i + 1
    return total


def range_loop(n):
    total = 0
    for i in range(n):
        total = total + i
    return total


def step(i):
    return i * 3 + 7


def calls(n):
    total = 0
    for i in range(n):
        total = total + step(i)
    return total


def collections(n):
    values = []
    for i in range(n):
        values.append(i + 1)
    total = 0
    for i in range(n):
        total = total + values[i]
    return total
