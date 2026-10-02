values = [0.0, -0.0, 0.1, -0.1, 0.5, 1.5, 2.5, -2.5, 2.675, -2.675, 1.005, 1.015, 5e15-1, 5e15+1, 1e20, 1e-300, -1e-300, 5e-324, -5e-324, 1.7976931348623157e308]
for value in values:
    print(round(value), type(round(value)).__name__)
    for digits in [None, -309, -308, -307, -20, -2, -1, 0, 1, 2, 15, 16, 300, 308, 323, 324, 10**100, -(10**100)]:
        try:
            result = round(value, digits)
            print(str(result), type(result).__name__)
        except OverflowError: print('OverflowError')
for value in [float('nan'), float('inf'), -float('inf')]:
    for digits in [None, 0, 2, -(10**100)]:
        try: print(round(value, digits))
        except ValueError: print('ValueError')
        except OverflowError: print('OverflowError')
    try: round(value, 1.0)
    except TypeError: print('index before nonfinite')
# Spread binary64 inputs across magnitudes and decimal rounding boundaries.
for index in range(-40, 41):
    value = (index * 1234567 + 5) / 100000
    for digits in [-3, -1, 0, 1, 2, 5, 10]:
        print(format(round(value, digits), '.17g'))
