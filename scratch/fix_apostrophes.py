import os
import re

def escape_apostrophes(text):
    # This regex looks for an apostrophe that is not preceded by a backslash
    return re.sub(r"(?<!\\)'", r"\'", text)

res_dir = "core/resources/src/main/res"

for root, dirs, files in os.walk(res_dir):
    for file in files:
        if file == "strings.xml":
            file_path = os.path.join(root, file)
            with open(file_path, 'r', encoding='utf-8') as f:
                content = f.read()
            
            # Use regex to find text within xml tags and escape apostrophes
            # This is safer than replacing all apostrophes, as it avoids xml attributes
            # Actually, Android AAPT doesn't care if attributes have escaped apostrophes
            # But let's only replace apostrophes inside element content.
            # A simpler way: replace all unescaped apostrophes except inside quotes
            # Or just use ET to parse, update text, and save.
            pass

import xml.etree.ElementTree as ET

def fix_apostrophes(locale_path):
    try:
        tree = ET.parse(locale_path)
        root = tree.getroot()
    except Exception as e:
        print(f"Failed to parse {locale_path}: {e}")
        return False
        
    modified = False
    
    for child in root:
        if child.tag == 'string' and child.text:
            new_text = escape_apostrophes(child.text)
            if new_text != child.text:
                child.text = new_text
                modified = True
        elif child.tag == 'plurals':
            for item in child:
                if item.text:
                    new_text = escape_apostrophes(item.text)
                    if new_text != item.text:
                        item.text = new_text
                        modified = True
        elif child.tag == 'string-array':
            for item in child:
                if item.text:
                    new_text = escape_apostrophes(item.text)
                    if new_text != item.text:
                        item.text = new_text
                        modified = True

    if modified:
        ET.indent(root, space="    ", level=0)
        rough_string = ET.tostring(root, encoding='utf-8').decode('utf-8')
        with open(locale_path, 'w', encoding='utf-8') as f:
            f.write('<?xml version="1.0" encoding="utf-8"?>\n')
            f.write(rough_string)
        print(f"Fixed {locale_path}")
        return True
    return False

for folder in sorted(os.listdir(res_dir)):
    if folder.startswith("values"):
        locale_path = os.path.join(res_dir, folder, "strings.xml")
        if os.path.exists(locale_path):
            fix_apostrophes(locale_path)
