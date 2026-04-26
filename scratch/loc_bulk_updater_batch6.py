import os
import xml.etree.ElementTree as ET

def rebuild_locale(res_dir, locale_name, translation_dict):
    base_path = os.path.join(res_dir, "values", "strings.xml")
    target_dir = os.path.join(res_dir, f"values-{locale_name}")
    if not os.path.exists(target_dir):
        os.makedirs(target_dir)
        
    target_path = os.path.join(target_dir, "strings.xml")
    tree = ET.parse(base_path)
    root = tree.getroot()
    new_root = ET.Element('resources')
    
    for child in root:
        if child.tag == 'string':
            name = child.get('name')
            translatable = child.get('translatable', 'true')
            new_node = ET.SubElement(new_root, 'string', {'name': name})
            if translatable == 'false':
                new_node.set('translatable', 'false')
                new_node.text = child.text
            else:
                val = translation_dict.get(name)
                new_node.text = val if val else child.text
        elif child.tag == 'plurals':
            name = child.get('name')
            new_node = ET.SubElement(new_root, 'plurals', {'name': name})
            plural_data = translation_dict.get(f"plural:{name}", {})
            for item in child:
                qty = item.get('quantity')
                new_item = ET.SubElement(new_node, 'item', {'quantity': qty})
                val = plural_data.get(qty)
                new_item.text = val if val else item.text
        elif child.tag == 'string-array':
            name = child.get('name')
            new_node = ET.SubElement(new_root, 'string-array', {'name': name})
            array_data = translation_dict.get(f"array:{name}", [])
            for i, item in enumerate(child):
                new_item = ET.SubElement(new_node, 'item')
                if i < len(array_data):
                    new_item.text = array_data[i]
                else:
                    new_item.text = item.text

    ET.indent(new_root, space="    ", level=0)
    rough_string = ET.tostring(new_root, encoding='utf-8').decode('utf-8')
    with open(target_path, 'w', encoding='utf-8') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n')
        f.write(rough_string)

# MALAYALAM (ml)
ml_data = {
    "common_back": "പിന്നോട്ട്", "common_cancel": "റദ്ദാക്കുക", "common_save": "സേവ്", "common_delete": "ഡിലീറ്റ്",
    "common_open": "തുറക്കുക", "common_refresh": "പുതുക്കുക", "common_continue": "തുടരുക", "common_accept": "സമ്മതിക്കുക",
    "common_decline": "നിരസിക്കുക", "common_dismiss": "മാറ്റുക", "common_remove": "നീക്കം ചെയ്യുക", "common_search": "തിരയുക",
    "onboarding_title": "DirectServe", "onboarding_subtitle": "പ്രാദേശിക ഫയൽ ഷെയറിംഗ്.",
    "onboarding_page_1_title": "ഏതു സ്ക്രീനിലും", "onboarding_page_1_description": "ടിവിയിലും കമ്പ്യൂട്ടറിലും.",
    "onboarding_get_started": "തുടങ്ങാം", "settings_title": "സെറ്റിംഗ്സ്",
    "settings_subtitle": "നിങ്ങളുടെ മുൻഗണനകൾ.", "settings_group_general": "പൊതുവായവ",
    "settings_group_notifications": "അറിയിപ്പുകൾ", "settings_group_privacy": "സ്വകാര്യത",
    "settings_language": "ഭാഷ", "settings_theme": "തീം", "settings_theme_dark": "ഡാർക്ക്",
    "settings_theme_light": "ലൈറ്റ്", "home_brand_title": "DirectServe", "home_brand_subtitle": "സ്വകാര്യ പങ്കിടൽ",
    "home_button_start_session": "തുടങ്ങുക", "home_button_add_media": "കൂട്ടുക",
    "home_title_live": "സെഷൻ സജീവം", "home_no_network": "വൈഫൈ ഇല്ല",
    "session_title": "ലൈവ് സെഷൻ", "session_stop_sharing": "നിർത്തുക",
    "session_live_now": "ലൈവ്", "session_share_live": "സജീവം",
    "library_title": "ലൈബ്രറി", "library_empty_title": "ശൂന്യം",
    "library_empty_body": "എന്തെങ്കിലും ചേർക്കുക.", "library_add_title": "ചേർക്കുക",
    "library_add_files": "ഫയലുകൾ", "history_title": "ചരിത്രം",
    "history_empty_all": "ശൂന്യം.", "web_hero_eyebrow": "DirectServe",
    "web_hero_title": "നിങ്ങളുടെ ഫയലുകൾ", "web_nav_home": "ഹോം",
    "web_nav_media": "മീഡിയ", "web_nav_files": "ഫയലുകൾ",
    "web_nav_logout": "പുറത്തുകടക്കുക", "web_upload_title": "അപ്‌ലോഡ്",
}

# TAMIL (ta)
ta_data = {
    "common_back": "பின்செல்", "common_cancel": "ரத்து", "common_save": "சேமி", "common_delete": "அழி",
    "common_open": "திற", "common_refresh": "புதுப்பி", "common_continue": "தொடர்", "common_accept": "ஏற்க",
    "common_decline": "நிராகரி", "common_dismiss": "மூடு", "common_remove": "நீக்கு", "common_search": "தேடு",
    "onboarding_title": "DirectServe", "onboarding_subtitle": "உள்ளூர் கோப்பு பகிர்வு.",
    "onboarding_page_1_title": "எந்த திரையிலும்", "onboarding_page_1_description": "டிவி மற்றும் கணினியில்.",
    "onboarding_get_started": "தொடங்கு", "settings_title": "அமைப்புகள்",
    "settings_subtitle": "உங்கள் விருப்பங்கள்.", "settings_group_general": "பொது",
    "settings_group_notifications": "அறிவிப்புகள்", "settings_group_privacy": "தனியுரிமை",
    "settings_language": "மொழி", "settings_theme": "தோற்றம்", "settings_theme_dark": "இருண்ட",
    "settings_theme_light": "வெளிச்சம்", "home_brand_title": "DirectServe", "home_brand_subtitle": "தனியார் பகிர்வு",
    "home_button_start_session": "தொடங்கு", "home_button_add_media": "சேர்",
    "home_title_live": "பகிர்வு நடக்கிறது", "home_no_network": "வைஃபை இல்லை",
    "session_title": "நேரலை பகிர்வு", "session_stop_sharing": "நிறுத்து",
    "session_live_now": "நேரலை", "session_share_live": "செயலில் உள்ளது",
    "library_title": "நூலகம்", "library_empty_title": "காலி",
    "library_empty_body": "எதையாவது சேர்க்கவும்.", "library_add_title": "சேர்",
    "library_add_files": "கோப்புகள்", "history_title": "வரலாறு",
    "history_empty_all": "காலி.", "web_hero_eyebrow": "DirectServe",
    "web_hero_title": "உங்கள் கோப்புகள்", "web_nav_home": "முகப்பு",
    "web_nav_media": "மீடியா", "web_nav_files": "கோப்புகள்",
    "web_nav_logout": "வெளியேறு", "web_upload_title": "பதிவேற்றம்",
}

# TELUGU (te)
te_data = {
    "common_back": "వెనుకకు", "common_cancel": "రద్దు", "common_save": "సేవ్", "common_delete": "తొలగించు",
    "common_open": "తెరువు", "common_refresh": "రిఫ్రెష్", "common_continue": "కొనసాగించు", "common_accept": "అంగీకరించు",
    "common_decline": "తిరస్కరించు", "common_dismiss": "మూసివేయు", "common_remove": "తీసివేయు", "common_search": "వెతుకు",
    "onboarding_title": "DirectServe", "onboarding_subtitle": "లోకల్ ఫైల్ షేరింగ్.",
    "onboarding_page_1_title": "ఏ స్క్రీన్ పైనైనా", "onboarding_page_1_description": "టీవీ మరియు కంప్యూటర్.",
    "onboarding_get_started": "ప్రారంభించు", "settings_title": "సెట్టింగ్స్",
    "settings_subtitle": "మీ ప్రాధాన్యతలు.", "settings_group_general": "సాధారణ",
    "settings_group_notifications": "నోటిఫికేషన్లు", "settings_group_privacy": "గోప్యత",
    "settings_language": "భాష", "settings_theme": "థీమ్", "settings_theme_dark": "డార్క్",
    "settings_theme_light": "లైట్", "home_brand_title": "DirectServe", "home_brand_subtitle": "ప్రైవేట్ షేరింగ్",
    "home_button_start_session": "ప్రారంభించు", "home_button_add_media": "జోడించు",
    "home_title_live": "షేరింగ్ ఆన్‌లో ఉంది", "home_no_network": "వైఫై లేదు",
    "session_title": "లైవ్ సెషన్", "session_stop_sharing": "ఆపు",
    "session_live_now": "లైవ్", "session_share_live": "యాక్టివ్",
    "library_title": "లైబ్రరీ", "library_empty_title": "ఖాళీ",
    "library_empty_body": "ఏదైనా జోడించండి.", "library_add_title": "జోడించు",
    "library_add_files": "ఫైళ్లు", "history_title": "చరిత్ర",
    "history_empty_all": "ఖాళీ.", "web_hero_eyebrow": "DirectServe",
    "web_hero_title": "మీ ఫైళ్లు", "web_nav_home": "హోమ్",
    "web_nav_media": "మీడియా", "web_nav_files": "ఫైళ్లు",
    "web_nav_logout": "లాగౌట్", "web_upload_title": "అప్‌లోడ్",
}

# THAI (th)
th_data = {
    "common_back": "กลับ", "common_cancel": "ยกเลิก", "common_save": "บันทึก", "common_delete": "ลบ",
    "common_open": "เปิด", "common_refresh": "รีเฟรช", "common_continue": "ดำเนินการต่อ", "common_accept": "ยอมรับ",
    "common_decline": "ปฏิเสธ", "common_dismiss": "ปิด", "common_remove": "เอาออก", "common_search": "ค้นหา",
    "onboarding_title": "DirectServe", "onboarding_subtitle": "การแชร์ไฟล์ในเครื่อง.",
    "onboarding_page_1_title": "บนหน้าจอใดก็ได้", "onboarding_page_1_description": "ทีวีและคอมพิวเตอร์.",
    "onboarding_get_started": "เริ่มต้น", "settings_title": "การตั้งค่า",
    "settings_subtitle": "การตั้งค่าของคุณ.", "settings_group_general": "ทั่วไป",
    "settings_group_notifications": "การแจ้งเตือน", "settings_group_privacy": "ความเป็นส่วนตัว",
    "settings_language": "ภาษา", "settings_theme": "ธีม", "settings_theme_dark": "มืด",
    "settings_theme_light": "สว่าง", "home_brand_title": "DirectServe", "home_brand_subtitle": "แชร์ส่วนตัว",
    "home_button_start_session": "เริ่ม", "home_button_add_media": "เพิ่ม",
    "home_title_live": "เซสชันเปิดอยู่", "home_no_network": "ไม่มี Wi-Fi",
    "session_title": "เซสชันสด", "session_stop_sharing": "หยุด",
    "session_live_now": "สด", "session_share_live": "ทำงานอยู่",
    "library_title": "ไลบรารี", "library_empty_title": "ว่าง",
    "library_empty_body": "เพิ่มบางอย่าง.", "library_add_title": "เพิ่ม",
    "library_add_files": "ไฟล์", "history_title": "ประวัติ",
    "history_empty_all": "ว่าง.", "web_hero_eyebrow": "DirectServe",
    "web_hero_title": "ไฟล์ของคุณ", "web_nav_home": "หน้าแรก",
    "web_nav_media": "สื่อ", "web_nav_files": "ไฟล์",
    "web_nav_logout": "ออกจากระบบ", "web_upload_title": "อัปโหลด",
}

# Apply batch
res_dir = "core/resources/src/main/res"
rebuild_locale(res_dir, "ml", ml_data)
rebuild_locale(res_dir, "ta", ta_data)
rebuild_locale(res_dir, "te", te_data)
rebuild_locale(res_dir, "th", th_data)
