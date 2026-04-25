import os
import xml.etree.ElementTree as ET
import re

def parse_strings(file_path):
    if not os.path.exists(file_path):
        return {}
    try:
        tree = ET.parse(file_path)
        root = tree.getroot()
        strings = {}
        for child in root:
            if child.tag == 'string':
                name = child.get('name')
                text = child.text or ""
                # Replace %% with something that won't match our placeholder regex
                temp_text = text.replace('%%', 'PLACEHOLDER_ESC_PERCENT')
                placeholders = re.findall(r'%(\d+\$)?[-+# 0,(]*[\d\.]*[A-Za-z]', temp_text)
                strings[name] = {
                    'type': 'string',
                    'text': text,
                    'placeholders': sorted(placeholders)
                }
            elif child.tag == 'plurals':
                name = child.get('name')
                items = {}
                all_placeholders = set()
                for item in child:
                    text = item.text or ""
                    items[item.get('quantity')] = text
                    temp_text = text.replace('%%', 'PLACEHOLDER_ESC_PERCENT')
                    placeholders = re.findall(r'%(\d+\$)?[-+# 0,(]*[\d\.]*[A-Za-z]', temp_text)
                    for p in placeholders:
                        all_placeholders.add(p)
                strings[name] = {
                    'type': 'plurals',
                    'items': items,
                    'placeholders': sorted(list(all_placeholders))
                }
        return strings
    except Exception as e:
        print(f"Error parsing {file_path}: {e}")
        return {}

def audit_locales(base_path):
    english_path = os.path.join(base_path, 'values', 'strings.xml')
    english_strings = parse_strings(english_path)
    
    locales = [d for d in os.listdir(base_path) if d.startswith('values-')]
    
    report = []
    
    for locale in locales:
        locale_path = os.path.join(base_path, locale, 'strings.xml')
        if not os.path.exists(locale_path):
            continue
            
        locale_strings = parse_strings(locale_path)
        
        missing = [k for k in english_strings if k not in locale_strings]
        extra = [k for k in locale_strings if k not in english_strings]
        
        placeholder_mismatch = []
        for k in english_strings:
            if k in locale_strings:
                if english_strings[k]['placeholders'] != locale_strings[k]['placeholders']:
                    placeholder_mismatch.append({
                        'key': k,
                        'expected': english_strings[k]['placeholders'],
                        'actual': locale_strings[k]['placeholders']
                    })
        
        report.append({
            'locale': locale,
            'missing_count': len(missing),
            'extra_count': len(extra),
            'mismatch_count': len(placeholder_mismatch),
            'missing': missing, 
            'extra': extra,
            'mismatch': placeholder_mismatch
        })
        
    return report

if __name__ == "__main__":
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    res_path = os.path.join(base_dir, 'core', 'resources', 'src', 'main', 'res')
    results = audit_locales(res_path)
    for res in results:
        print(f"LOCALE_START:{res['locale']}")
        print(f"MISSING:{','.join(res['missing'])}") # This only shows first 10, need more for automation
        # I'll modify the function to return ALL missing keys
        print(f"EXTRA:{','.join(res['extra'])}")
        print(f"MISMATCH:{res['mismatch']}")
        print(f"LOCALE_END")
