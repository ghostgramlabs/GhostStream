with open(r"c:\Users\sudhi\.gemini\antigravity\scratch\GhostStream\feature\home\src\main\java\com\ghoststream\feature\home\HomeScreen.kt", "r", encoding="utf-8") as f:
    lines = f.readlines()

for i, line in enumerate(lines):
    if "FeatureSections" in line:
        print(f"Line {i+1}: {line.strip()}")
