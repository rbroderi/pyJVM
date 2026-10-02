for text in ('abc', 'aé🙂', ''):
    for spec in ('', 's', '8', '>8', '^8', '*<8', '🙂^8', '.2', '8.2s', '05s', '0>5', '^07s'):
        print(repr(format(text, spec)))
for value in (0, 1, -1, 17, -1234567, 2**100, True):
    for spec in ('', 'd', '+d', ' d', '08d', '#08x', '#X', '#b', '#o', '*^20d', '010,d', ',d', '_d', '#_x', '#020_x', 'n', '<10d'):
        print(format(value, spec))
for value in (65, 0x1f642):
    print(repr(format(value, 'c')), repr(format(value, '^5c')))
for value,spec in [('s', '+s'),('s', '=5'),('s','#s'),('s','zs'),('s',',s'),('s','d'),(1,'.2d'),(1,'zd'),(1,',x'),(1,'+c'),(1,'#c'),(1,'s'),(1,'q'),('s','.'),(1,'10dd')]:
    try: print(format(value, spec))
    except ValueError: print('ValueError')
print(repr(format(-17, ' >08d')), repr(format('a', ' >05s')))
