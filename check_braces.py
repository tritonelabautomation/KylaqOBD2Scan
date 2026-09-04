with open("app/src/main/java/com/example/MainActivity.kt", "r") as f:
    text = f.read()

count = 0
for i, c in enumerate(text):
    if c == '{': count += 1
    elif c == '}': count -= 1
    if count < 0:
        print(f"Unmatched }} at index {i}")
        break

print(f"Final count: {count}")
