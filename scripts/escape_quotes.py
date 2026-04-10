import os, glob

print("Escaping single quotes...")
count = 0
for path in glob.glob('core/resources/src/main/res/values*/strings.xml'):
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # Replace any escaped quote with normal quote first, so we don't double escape
    content = content.replace("\\'", "'")
    # Now escape all single quotes
    content = content.replace("'", "\\'")
    
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)
    count += 1

print(f"Fixed quotes in {count} files.")
