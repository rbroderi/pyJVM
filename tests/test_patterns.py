import pytest
from pyjvm315.compiler import CompileError, compile_source


@pytest.mark.parametrize('source,reason', [
    ('match 1:\n    case (1 as x) | (2 as y): pass\n', 'different names'),
    ('match 1:\n    case x: pass\n    case 1: pass\n', 'remaining cases unreachable'),
    ('match 1:\n    case x | 1: pass\n', 'remaining patterns unreachable'),
    ('match 1:\n    case (1 as x) as x: pass\n', 'duplicate capture'),
    ('match [1]:\n    case [x]: pass\n', 'MatchSequence'),
    ('match {}:\n    case {}: pass\n', 'MatchMapping'),
    ('match 1:\n    case int(): pass\n', 'MatchClass'),
])
def test_scalar_match_rejects_invalid_or_unimplemented_patterns(source, reason):
    with pytest.raises(CompileError, match=reason):
        compile_source(source)
