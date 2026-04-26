import os
import xml.etree.ElementTree as ET
import re
import time
from deep_translator import GoogleTranslator

LANG_MAP = {
    "values-af": "af", "values-ar": "ar", "values-de": "de", "values-el": "el",
    "values-es": "es", "values-es-rUS": "es", "values-fr": "fr", "values-hi": "hi",
    "values-id": "id", "values-in": "id", "values-it": "it", "values-ja": "ja",
    "values-ko": "ko", "values-ml": "ml", "values-nl": "nl", "values-pt": "pt",
    "values-pt-rBR": "pt", "values-ru": "ru", "values-sv": "sv", "values-ta": "ta",
    "values-te": "te", "values-th": "th", "values-tr": "tr", "values-vi": "vi",
    "values-zh-rCN": "zh-CN", "values-zh-rTW": "zh-TW"
}

# Technical terms that are often the same in many languages or shouldn't be blindly translated if they are the ONLY content.
TECHNICAL_TERMS = ["DirectServe", "DLNA", "Wi-Fi", "QR", "HLS", "WebRTC", "PIN", "IP", "MP4", "VLC", "Kodi"]

def get_placeholders(text):
    if text is None: return []
    return [m.group(0) for m in re.finditer(r'%(\d+\$)?[-#+ 0,(\.<]*\d*(\.\d+)?[a-zA-Z%]', text) if m.group(0) != '%%']

def escape_apostrophes(text):
    return re.sub(r"(?<!\\)'", r"\'", text) if text else text

def load_xml(path):
    try:
        tree = ET.parse(path)
        root = tree.getroot()
        return tree, root
    except:
        return None, None

def is_suspicious(text, english_text):
    if not text: return True
    if text != english_text: return False
    
    # If text is English and not a pure technical term
    clean_text = text.strip()
    if clean_text in TECHNICAL_TERMS: return False
    
    # If it's a long sentence in English, it's definitely suspicious
    if len(clean_text.split()) > 1: return True
    
    # If it contains common English words
    common_english = ["and", "the", "with", "from", "your", "files", "share", "connect", "browser"]
    if any(word in clean_text.lower() for word in common_english): return True
    
    return False

def mask_placeholders(text):
    placeholders = get_placeholders(text)
    masked_text = text
    for i, p in enumerate(placeholders):
        masked_text = masked_text.replace(p, f" __P{i}__ ", 1)
    return masked_text, placeholders

def unmask_placeholders(text, placeholders):
    unmasked = text
    for i, p in enumerate(placeholders):
        unmasked = re.sub(rf"__P{i}__", p, unmasked, flags=re.IGNORECASE)
    # Clean up spaces around placeholders that Google Translate might have added
    for p in placeholders:
        unmasked = unmasked.replace(f" {p}", p).replace(f"{p} ", p)
    return unmasked

def main():
    res_dir = "core/resources/src/main/res"
    english_path = os.path.join(res_dir, "values", "strings.xml")
    english_tree, english_root = load_xml(english_path)
    
    # Load all English strings and plurals
    base_strings = {c.get('name'): c.text for c in english_root if c.tag == 'string'}
    base_plurals = {c.get('name'): {item.get('quantity'): item.text for item in c} for c in english_root if c.tag == 'plurals'}

    for folder, lang_code in LANG_MAP.items():
        path = os.path.join(res_dir, folder, "strings.xml")
        if not os.path.exists(path): continue
        
        print(f"\nScanning {folder} ({lang_code})...")
        tree, root = load_xml(path)
        if not root: continue
        
        translator = GoogleTranslator(source='en', target=lang_code)
        modified = False
        
        # Repair Strings
        for key, english_text in base_strings.items():
            if not english_text: continue
            node = root.find(f"./string[@name='{key}']")
            
            if node is None or is_suspicious(node.text, english_text):
                reason = "Missing" if node is None else "Same as English"
                print(f"  [{reason}] Translating '{key}'...")
                try:
                    masked, placeholders = mask_placeholders(english_text)
                    translated = translator.translate(masked)
                    final_text = unmask_placeholders(translated, placeholders)
                    final_text = final_text.replace("GhostStream", "DirectServe").replace("GhostGram", "DirectServe")
                    
                    if node is None:
                        node = ET.SubElement(root, 'string', {'name': key})
                    node.text = escape_apostrophes(final_text)
                    modified = True
                    time.sleep(0.5) # Throttle
                except Exception as e:
                    print(f"    Error translating {key}: {e}")
        
        # Repair Plurals
        for key, base_items in base_plurals.items():
            plural_node = root.find(f"./plurals[@name='{key}']")
            if plural_node is None:
                plural_node = ET.SubElement(root, 'plurals', {'name': key})
                modified = True
            
            for qty, english_text in base_items.items():
                item_node = plural_node.find(f"./item[@quantity='{qty}']")
                if item_node is None or is_suspicious(item_node.text, english_text):
                    reason = "Missing" if item_node is None else "Same as English"
                    print(f"  [{reason}] Translating plural '{key}' ({qty})...")
                    try:
                        masked, placeholders = mask_placeholders(english_text)
                        translated = translator.translate(masked)
                        final_text = unmask_placeholders(translated, placeholders)
                        final_text = final_text.replace("GhostStream", "DirectServe").replace("GhostGram", "DirectServe")
                        
                        if item_node is None:
                            item_node = ET.SubElement(plural_node, 'item', {'quantity': qty})
                        item_node.text = escape_apostrophes(final_text)
                        modified = True
                        time.sleep(0.5)
                    except Exception as e:
                        print(f"    Error translating plural {key}: {e}")

        if modified:
            ET.indent(root, space="    ", level=0)
            with open(path, 'w', encoding='utf-8') as f:
                f.write('<?xml version="1.0" encoding="utf-8"?>\n')
                f.write(ET.tostring(root, encoding='utf-8').decode('utf-8'))
            print(f"  Saved {folder}")

if __name__ == "__main__":
    main()
