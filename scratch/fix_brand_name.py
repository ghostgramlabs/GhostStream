import os
import xml.etree.ElementTree as ET

LANG_MAP = {
    "values-af": "af", "values-ar": "ar", "values-de": "de", "values-el": "el",
    "values-es": "es", "values-es-rUS": "es", "values-fr": "fr", "values-hi": "hi",
    "values-id": "id", "values-in": "id", "values-it": "it", "values-ja": "ja",
    "values-ko": "ko", "values-ml": "ml", "values-nl": "nl", "values-pt": "pt",
    "values-pt-rBR": "pt", "values-ru": "ru", "values-sv": "sv", "values-ta": "ta",
    "values-te": "te", "values-th": "th", "values-tr": "tr", "values-vi": "vi",
    "values-zh-rCN": "zh-CN", "values-zh-rTW": "zh-TW"
}

def main():
    res_dir = "core/resources/src/main/res"
    for folder in LANG_MAP:
        path = os.path.join(res_dir, folder, "strings.xml")
        if not os.path.exists(path): continue
        
        tree = ET.parse(path)
        root = tree.getroot()
        modified = False
        
        # Keys where DirectServe should be English
        keys_to_check = ["settings_rate_app"]
        
        for key in keys_to_check:
            node = root.find(f"./string[@name='{key}']")
            if node is not None and node.text:
                if "直接服務" in node.text:
                    node.text = node.text.replace("直接服務", "DirectServe")
                    modified = True
                # Add other known translations of DirectServe if found
        
        if modified:
            ET.indent(root, space="    ", level=0)
            with open(path, 'w', encoding='utf-8') as f:
                f.write('<?xml version="1.0" encoding="utf-8"?>\n')
                f.write(ET.tostring(root, encoding='utf-8').decode('utf-8'))
            print(f"Fixed brand name in {folder}")

if __name__ == "__main__":
    main()
