import xml.etree.ElementTree as ET
import os
import sys

from deep_translator import GoogleTranslator

target_locales = [
    "af", "ar", "de", "en", "es", "fr", "hi", "in", "it", "ja",
    "ko", "nl", "pt", "pt-rBR", "ru", "sv", "th", "tr", "vi", "zh-rCN", "zh-rTW"
]

strings_to_add = {
    "web_upload_title": "Send to Device",
    "web_upload_subtitle": "Upload files to the host device over the network.",
    "web_upload_prompt_title": "Select files to send",
    "web_upload_prompt_desktop": "Drag and drop here, or tap the button below",
    "web_upload_prompt_mobile": "Tap the button to select files from your library",
    "web_upload_button_browse": "Browse Files",
    "web_upload_target_kicker": "Target Device",
    "web_upload_target_status": "Status: Connected and ready for transfers",
    "web_upload_how_kicker": "How it works",
    "web_upload_how_title": "Secure Approval",
    "web_upload_how_body": "<![CDATA[When you send a file, a notification will appear on the phone. The device owner must <strong>Accept</strong> for the transfer to begin.]]>",
    "web_action_download": "Download",
    "web_photo_view": "View",
    "web_btn_download_all": "Download all",
    "web_btn_download_selected": "Download selected",
    "web_btn_download_original": "Download original",
    "web_error_streaming_codec": "This file's codec is not supported by the Android server for streaming. Please download.",
    "web_error_downloads_disabled": "Downloads are disabled by the device owner.",
    "web_error_video_decode": "This browser could not decode the video stream. Try downloading the original file.",
    "web_error_video_start": "This browser could not start the video. Try again or download the original file.",
}

google_trans_map = {
    "af": "af", "ar": "ar", "de": "de", "en": "en", "es": "es", "fr": "fr",
    "hi": "hi", "in": "id", "it": "it", "ja": "ja", "ko": "ko", "nl": "nl",
    "pt": "pt", "pt-rBR": "pt", "ru": "ru", "sv": "sv", "th": "th", "tr": "tr",
    "vi": "vi", "zh-rCN": "zh-cn", "zh-rTW": "zh-tw"
}

def translate_html_safe(text, dest_lang):
    if "CDATA" in text:
        inner = text.replace("<![CDATA[", "").replace("]]>", "")
        safe = inner.replace("<strong>", "{{B}}").replace("</strong>", "{{/B}}")
        res = GoogleTranslator(source='en', target=dest_lang).translate(safe)
        res = res.replace("{{B}}", "<strong>").replace("{{/B}}", "</strong>")
        return f"<![CDATA[{res}]]>"
    else:
        return GoogleTranslator(source='en', target=dest_lang).translate(text)

base_dir = "core/resources/src/main/res"

print("Starting to translate elements...")
for lang in target_locales:
    folder_name = "values" if lang == "en" else f"values-{lang}"
    file_path = os.path.join(base_dir, folder_name, "strings.xml")

    if not os.path.exists(file_path):
        os.makedirs(os.path.dirname(file_path), exist_ok=True)
        with open(file_path, "w", encoding="utf-8") as f:
            f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n')

    try:
        tree = ET.parse(file_path)
    except Exception as e:
        print(f"Skipping {file_path} because of {e}")
        continue
    root = tree.getroot()

    existing_keys = [child.attrib.get('name') for child in root if child.tag == 'string']

    changed = False
    for k, v in strings_to_add.items():
        if k not in existing_keys:
            target_code = google_trans_map[lang]
            if lang == "en":
                translated_val = v
            else:
                try:
                    translated_val = translate_html_safe(v, target_code)
                except Exception as e:
                    print(f"Failed {k} {lang}: {e}")
                    translated_val = v
            
            elem = ET.Element('string', name=k)
            # handle quotes escaping outside CDATA
            if "CDATA" not in translated_val:
                translated_val = translated_val.replace("'", "\\'")
            elem.text = translated_val
            root.append(elem)
            changed = True
            print(f"Added {k} to {lang}")

    if changed:
        raw_xml = ET.tostring(root, encoding='utf-8').decode('utf-8')
        raw_xml = raw_xml.replace("&lt;![CDATA[", "<![CDATA[").replace("]]&gt;", "]]>")
        with open(file_path, "w", encoding="utf-8") as f:
            f.write('<?xml version="1.0" encoding="utf-8"?>\n')
            f.write(raw_xml)

print("Translation Chunk 5 Done.")
