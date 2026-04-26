import os
import xml.etree.ElementTree as ET

def get_strings(file_path):
    if not os.path.exists(file_path):
        return {}
    try:
        tree = ET.parse(file_path)
        root = tree.getroot()
        strings = {}
        # Handle <string>
        for s in root.findall('string'):
            name = s.get('name')
            if name:
                strings[name] = s.text or ""
        # Handle <plurals>
        for p in root.findall('plurals'):
            p_name = p.get('name')
            if p_name:
                items = {}
                for item in p.findall('item'):
                    items[item.get('quantity')] = item.text or ""
                strings[p_name] = items
        return strings
    except Exception as e:
        print(f"Error parsing {file_path}: {e}")
        return {}

def main():
    base_path = "core/resources/src/main/res"
    en_strings = get_strings(os.path.join(base_path, "values", "strings.xml"))
    
    locales = [d for d in os.listdir(base_path) if d.startswith("values-")]
    
    ignore_list = ["PIN", "DLNA", "QR", " | ", " • ", "DirectServe", "GhostStream", "WebRTC", "Ktor", "Android", "iOS", "Windows", "Mac", "Linux", "Chrome", "Safari", "Firefox", "Edge"]
    
    for locale in locales:
        loc_file = os.path.join(base_path, locale, "strings.xml")
        loc_strings = get_strings(loc_file)
        
        untranslated = []
        for key, value in loc_strings.items():
            if key in en_strings:
                en_value = en_strings[key]
                if value == en_value:
                    # If it's a string and not in ignore list
                    if isinstance(value, str):
                        if value.strip() and value not in ignore_list and len(value) > 3:
                            untranslated.append(f"STRING:{key} -> {value}")
                    # If it's plurals
                    elif isinstance(value, dict):
                        is_same = True
                        for q, v in value.items():
                            if v != en_value.get(q):
                                is_same = False
                                break
                        if is_same:
                            untranslated.append(f"PLURALS:{key}")
        
        if untranslated:
            print(f"\n[{locale}] Potentially untranslated:")
            for item in untranslated:
                print(f"  {item}")

if __name__ == "__main__":
    main()
