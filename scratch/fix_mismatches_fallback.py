import os
import xml.etree.ElementTree as ET
import re

def get_placeholders(text):
    if text is None:
        return []
    pattern = r'%(\d+\$)?[-#+ 0,(\.<]*\d*(\.\d+)?[a-zA-Z%]'
    placeholders = []
    for m in re.finditer(pattern, text):
        ph = m.group(0)
        if ph != '%%':
            placeholders.append(ph)
    return placeholders

def load_base(base_path):
    tree = ET.parse(base_path)
    root = tree.getroot()
    strings = {}
    plurals = {}
    for child in root:
        if child.tag == 'string':
            name = child.get('name')
            strings[name] = child.text
        elif child.tag == 'plurals':
            name = child.get('name')
            items = {}
            for item in child:
                items[item.get('quantity')] = item.text
            plurals[name] = items
    return {'strings': strings, 'plurals': plurals}

def fix_locale(base_strings, locale_path):
    try:
        tree = ET.parse(locale_path)
        root = tree.getroot()
    except:
        return False
        
    modified = False
    
    for child in root:
        if child.tag == 'string':
            name = child.get('name')
            if name in base_strings['strings']:
                base_val = base_strings['strings'][name]
                loc_val = child.text
                if sorted(get_placeholders(base_val)) != sorted(get_placeholders(loc_val)):
                    print(f"Fixing string {name} in {locale_path}")
                    child.text = base_val
                    modified = True
                    
        elif child.tag == 'plurals':
            name = child.get('name')
            if name in base_strings['plurals']:
                base_items = base_strings['plurals'][name]
                for item in child:
                    qty = item.get('quantity')
                    base_qty_val = base_items.get(qty, base_items.get('other', ''))
                    loc_val = item.text
                    if sorted(get_placeholders(base_qty_val)) != sorted(get_placeholders(loc_val)):
                        print(f"Fixing plural {name}:{qty} in {locale_path}")
                        item.text = base_qty_val
                        modified = True

    if modified:
        ET.indent(root, space="    ", level=0)
        rough_string = ET.tostring(root, encoding='utf-8').decode('utf-8')
        with open(locale_path, 'w', encoding='utf-8') as f:
            f.write('<?xml version="1.0" encoding="utf-8"?>\n')
            f.write(rough_string)
        return True
    return False

if __name__ == "__main__":
    res_dir = "core/resources/src/main/res"
    base_path = os.path.join(res_dir, "values", "strings.xml")
    base_strings = load_base(base_path)
    
    for folder in sorted(os.listdir(res_dir)):
        if folder.startswith("values-"):
            locale_path = os.path.join(res_dir, folder, "strings.xml")
            if os.path.exists(locale_path):
                fix_locale(base_strings, locale_path)
    print("Fix completed.")
