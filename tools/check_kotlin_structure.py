#!/usr/bin/env python3
"""Brace/string/comment-aware balance checker for Kotlin sources.

The sandbox has no JDK, so this is the cheapest structural sanity check we can run
after editing: it strips line/block comments, char literals and string literals
(including triple-quoted and ${} interpolation) and then verifies that braces,
parentheses and brackets balance in every file.

Usage:
    python3 tools/check_kotlin_structure.py [path ...]
"""
import os
import sys


def scan(text):
    depth = {'{': 0, '(': 0, '[': 0}
    pairs = {'}': '{', ')': '(', ']': '['}
    i = 0
    n = len(text)
    errors = []
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ''
        if c == '/' and nxt == '/':
            while i < n and text[i] != '\n':
                i += 1
            continue
        if c == '/' and nxt == '*':
            i += 2
            while i + 1 < n and not (text[i] == '*' and text[i + 1] == '/'):
                i += 1
            i += 2
            continue
        if c == "'":
            i += 1
            while i < n and text[i] != "'":
                i += 2 if text[i] == '\\' else 1
            i += 1
            continue
        if c == '"':
            if text[i:i + 3] == '"""':
                i += 3
                while i + 2 < n and text[i:i + 3] != '"""':
                    i += 1
                i += 3
                continue
            i += 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == '\\' else 1
            i += 1
            continue
        if c in depth:
            depth[c] += 1
        elif c in pairs:
            depth[pairs[c]] -= 1
            if depth[pairs[c]] < 0:
                errors.append(f"unmatched '{c}' at offset {i} (line {text[:i].count(chr(10)) + 1})")
                depth[pairs[c]] = 0
        i += 1
    for opener, d in depth.items():
        if d != 0:
            errors.append(f"unbalanced '{opener}': depth {d} at EOF")
    return errors


def main(paths):
    files = []
    for p in paths:
        if os.path.isdir(p):
            for root, _dirs, names in os.walk(p):
                files += [os.path.join(root, f) for f in names if f.endswith('.kt')]
        elif p.endswith('.kt'):
            files.append(p)
    bad = 0
    for f in sorted(files):
        errs = scan(open(f, encoding='utf-8', errors='replace').read())
        if errs:
            bad += 1
            print(f"FAIL {f}")
            for e in errs:
                print(f"     {e}")
    print(f"checked {len(files)} file(s), {bad} with structural problems")
    return 1 if bad else 0


if __name__ == '__main__':
    args = sys.argv[1:] or ['app/src/main/java', 'app/src/test/java']
    sys.exit(main(args))
