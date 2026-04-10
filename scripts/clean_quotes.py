import os, glob

for path in glob.glob('core/resources/src/main/res/values*/strings.xml'):
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    if "\\\\" in content or "&lt;" in content:
        content = content.replace("\\\\'", "\\'")
        content = content.replace("&lt;", "<").replace("&gt;", ">")
        with open(path, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f"Fixed {path}")
