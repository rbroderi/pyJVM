for value in [-30, -1, 0, 1, 30, 2**53, 2**53+1, 2**100]:
    z = complex(value)
    print(z == value, value == z, z != value)
    if z == value:
        print(hash(z) == hash(value))
for value in [0.0, -0.0, 0.5, -2.5, float('inf'), float('-inf')]:
    z = complex(value)
    print(z == value, hash(z) == hash(value))
for z in [1j, 1+2j, 2000005-1j, complex(-0.0, -0.0), complex(1, float('inf'))]:
    print(hash(z), hash(z) == hash(z))
nan = complex(float('nan'), 1)
print(nan == nan, nan != nan, hash(nan) == hash(nan))
print(1j == None, 1j == '1j', 1j != '1j', 0j == False, 1+0j == True)
