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

KEYS_TO_FIX = [
    "home_feature_quick_text_disabled_live",
    "web_send_floating_title",
    "web_send_floating_subtitle",
    "web_upload_prompt_title",
    "web_send_files_to_device"
]

def escape_apostrophes(text):
    return re.sub(r"(?<!\\)'", r"\'", text) if text else text

def load_xml(path):
    try:
        tree = ET.parse(path)
        root = tree.getroot()
        return tree, root
    except:
        return None, None

def main():
    res_dir = "core/resources/src/main/res"
    english_tree, english_root = load_xml(os.path.join(res_dir, "values", "strings.xml"))
    
    # Get master English texts
    master_texts = {}
    for child in english_root:
        if child.tag == 'string' and child.get('name') in KEYS_TO_FIX:
            master_texts[child.get('name')] = child.text

    print(f"Master texts: {master_texts}")

    for folder, lang_code in LANG_MAP.items():
        path = os.path.join(res_dir, folder, "strings.xml")
        if not os.path.exists(path): continue
        
        print(f"Fixing {folder} ({lang_code})...")
        tree, root = load_xml(path)
        if not root: continue
        
        translator = GoogleTranslator(source='en', target=lang_code)
        
        modified = False
        for key in KEYS_TO_FIX:
            english_text = master_texts.get(key)
            if not english_text: continue
            
            # Find the node
            node = root.find(f"./string[@name='{key}']")
            
            # Condition to translate: node missing, OR node text is same as English, OR node text is empty
            if node is None or not node.text or node.text == english_text:
                print(f"  Translating '{key}'...")
                try:
                    translated = translator.translate(english_text)
                    translated = translated.replace("GhostStream", "DirectServe").replace("GhostGram", "DirectServe")
                    if node is None:
                        node = ET.SubElement(root, 'string', {'name': key})
                    node.text = escape_apostrophes(translated)
                    modified = True
                except Exception as e:
                    print(f"    Error translating {key}: {e}")
        
        if modified:
            ET.indent(root, space="    ", level=0)
            with open(path, 'w', encoding='utf-8') as f:
                f.write('<?xml version="1.0" encoding="utf-8"?>\n')
                f.write(ET.tostring(root, encoding='utf-8').decode('utf-8'))
            print(f"  Saved {folder}")
        time.sleep(1) # Be nice to the API

if __name__ == "__main__":
    main()
