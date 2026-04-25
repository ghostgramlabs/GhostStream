import os
import xml.etree.ElementTree as ET
import re
from xml.dom import minidom

def escape_android_string(s):
    if not s: return s
    # Standardize: unescape then escape to avoid double-escaping
    s = s.replace("\\'", "'")
    s = s.replace("'", "\\'")
    return s

def sync_locale(locale_name, base_res_path):
    en_path = os.path.join(base_res_path, 'values', 'strings.xml')
    locale_path = os.path.join(base_res_path, f'values-{locale_name}', 'strings.xml')
    
    if not os.path.exists(locale_path):
        print(f"Skipping {locale_name}, file not found.")
        return

    # Parse English keys and their types
    tree_en = ET.parse(en_path)
    root_en = tree_en.getroot()
    en_keys = {}
    for child in root_en:
        en_keys[child.get('name')] = child.tag

    # Parse Locale
    tree_loc = ET.parse(locale_path)
    root_loc = tree_loc.getroot()

    # Remove extra keys
    for child in list(root_loc):
        name = child.get('name')
        if name not in en_keys:
            root_loc.remove(child)
            print(f"[{locale_name}] Removed extra key: {name}")

    # Ensure correct tag types and add missing keys
    loc_keys = {child.get('name'): child for child in root_loc}
    for child_en in root_en:
        name = child_en.get('name')
        tag = child_en.tag
        
        if name in loc_keys:
            if loc_keys[name].tag != tag:
                root_loc.remove(loc_keys[name])
                # Clone English structure as placeholder
                new_el = ET.SubElement(root_loc, tag, name=name)
                if tag == 'plurals':
                    for item_en in child_en:
                        item = ET.SubElement(new_el, 'item', quantity=item_en.get('quantity'))
                        item.text = item_en.text
                else:
                    new_el.text = child_en.text
                print(f"[{locale_name}] Fixed tag type for: {name}")
        else:
            # Missing key, clone English
            new_el = ET.SubElement(root_loc, tag, name=name)
            if tag == 'plurals':
                for item_en in child_en:
                    item = ET.SubElement(new_el, 'item', quantity=item_en.get('quantity'))
                    item.text = item_en.text
            else:
                new_el.text = child_en.text
            print(f"[{locale_name}] Added missing key: {name}")

    # Write back with escaping
    for child in root_loc:
        if child.tag == 'string':
            if child.text:
                old = child.text
                child.text = escape_android_string(child.text)
                if old != child.text:
                    print(f"[{locale_name}] Escaped: {child.get('name')}")
        elif child.tag == 'plurals':
            for item in child:
                if item.text:
                    old = item.text
                    item.text = escape_android_string(item.text)
                    if old != item.text:
                        print(f"[{locale_name}] Escaped plural: {child.get('name')}")

    xml_str = ET.tostring(root_loc, encoding='utf-8')
    pretty_xml = minidom.parseString(xml_str).toprettyxml(indent="    ")
    
    with open(locale_path, "w", encoding="utf-8") as f:
        f.write(pretty_xml)
    print(f"[{locale_name}] Synchronized with English")

if __name__ == "__main__":
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    res_path = os.path.join(base_dir, 'core', 'resources', 'src', 'main', 'res')
    
    locales = ['af', 'ar', 'de', 'el', 'es', 'fr', 'hi', 'id', 'in', 'it', 'ja', 'ko', 'ml', 'nl', 'pt', 'pt-rBR', 'ru', 'sv', 'ta', 'te', 'th', 'tr', 'vi', 'zh-rCN', 'zh-rTW']
    for loc in locales:
        sync_locale(loc, res_path)
