import os

# Define the strings for Chunk 1: "Add to share" ActionShelf
keys = [
    "home_action_add_to_share_title",
    "home_action_add_to_share_subtitle",
    "home_action_files",
    "home_action_files_desc",
    "home_action_folder",
    "home_action_folder_desc",
    "home_action_library",
    "home_action_library_desc"
]

translations = {
    "values": ["Add to share", "Pick the files you want to send.", "Files", "Choose", "Folder", "Scan", "Library", "Browse"],
    "values-ar": ["إضافة للمشاركة", "اختر الملفات التي تريد إرسالها.", "ملفات", "اختيار", "مجلد", "مسح", "المكتبة", "تصفح"],
    "values-de": ["Zum Teilen hinzufügen", "Wählen Sie die Dateien aus, die Sie senden möchten.", "Dateien", "Auswählen", "Ordner", "Scannen", "Bibliothek", "Durchsuchen"],
    "values-es": ["Añadir para compartir", "Elige los archivos que quieres enviar.", "Archivos", "Elegir", "Carpeta", "Escanear", "Biblioteca", "Explorar"],
    "values-fr": ["Ajouter au partage", "Choisissez les fichiers à envoyer.", "Fichiers", "Choisir", "Dossier", "Analyser", "Bibliothèque", "Parcourir"],
    "values-hi": ["साझा करने के लिए जोड़ें", "वे फ़ाइलें चुनें जिन्हें आप भेजना चाहते हैं।", "फ़ाइलें", "चुनें", "फ़ोल्डर", "स्कैन", "लाइब्रेरी", "ब्राउज़"],
    "values-id": ["Tambahkan ke berbagi", "Pilih file yang ingin Anda kirim.", "File", "Pilih", "Folder", "Pindai", "Pustaka", "Jelajahi"],
    "values-it": ["Aggiungi alla condivisione", "Scegli i file che desideri inviare.", "File", "Scegli", "Cartella", "Scansiona", "Libreria", "Sfoglia"],
    "values-ja": ["共有に追加", "送信するファイルを選択してください。", "ファイル", "選択", "フォルダー", "スキャン", "ライブラリ", "参照"],
    "values-ko": ["공유에 추가", "보낼 파일을 선택하세요.", "파일", "선택", "폴더", "스캔", "라이브러리", "찾아보기"],
    "values-ml": ["ഷെയർ ചെയ്യാൻ ചേർക്കുക", "നിങ്ങൾക്ക് അയക്കേണ്ട ഫയലുകൾ തിരഞ്ഞെടുക്കുക.", "ഫയലുകൾ", "തിരഞ്ഞെടുക്കുക", "ഫോൾഡർ", "സ്കാൻ", "ലൈബ്രറി", "ബ്രൗസ്"],
    "values-nl": ["Toevoegen om te delen", "Kies de bestanden die u wilt verzenden.", "Bestanden", "Kiezen", "Map", "Scannen", "Bibliotheek", "Bladeren"],
    "values-pt": ["Adicionar à partilha", "Escolha os ficheiros que pretende enviar.", "Ficheiros", "Escolher", "Pasta", "Analisar", "Biblioteca", "Procurar"],
    "values-ru": ["Добавить для отправки", "Выберите файлы, которые хотите отправить.", "Файлы", "Выбрать", "Папка", "Сканировать", "Библиотека", "Обзор"],
    "values-ta": ["பகிரச் சேர்", "நீங்கள் அனுப்ப வேண்டிய கோப்புகளைத் தேர்வுசெய்யவும்.", "கோப்புகள்", "தேர்வுசெய்க", "கோப்புறை", "ஸ்கேன்", "நூலகம்", "உலாவு"],
    "values-te": ["షేర్ చేయడానికి జోడించండి", "మీరు పంపాలనుకుంటున్న ఫైల్‌లను ఎంచుకోండి.", "ఫైల్‌లు", "ఎంచుకోండి", "ఫోల్డర్", "స్కాన్ చేయండి", "లైబ్రరీ", "బ్రౌజ్ చేయండి"],
    "values-th": ["เพิ่มเพื่อแชร์", "เลือกไฟล์ที่คุณต้องการส่ง", "ไฟล์", "เลือก", "โฟลเดอร์", "สแกน", "คลัง", "เรียกดู"],
    "values-tr": ["Paylaşmak için ekle", "Göndermek istediğiniz dosyaları seçin.", "Dosyalar", "Seç", "Klasör", "Tara", "Kütüphane", "Gözat"],
    "values-vi": ["Thêm vào chia sẻ", "Chọn các tệp bạn muốn gửi.", "Tệp", "Chọn", "Thư mục", "Quét", "Thư viện", "Duyệt"],
    "values-zh-rCN": ["添加到共享", "选择您要发送的文件。", "文件", "选择", "文件夹", "扫描", "媒体库", "浏览"],
    "values-zh-rTW": ["加入至分享", "選擇您要傳送的檔案。", "檔案", "選擇", "資料夾", "掃描", "媒體庫", "瀏覽"]
}

base_dir = r"C:\Users\sudhi\.gemini\antigravity\scratch\GhostStream\core\resources\src\main\res"

for folder, strings_list in translations.items():
    file_path = os.path.join(base_dir, folder, "strings.xml")
    if os.path.exists(file_path):
        with open(file_path, "r", encoding="utf-8") as f:
            content = f.read()
        
        # Check if already added
        if keys[0] in content:
            print(f"Skipping {folder}, already contains {keys[0]}")
            continue

        insert_text = "\n    <!-- Action Shelf (Add to share) -->\n"
        for i, key in enumerate(keys):
            val = strings_list[i].replace("'", "\\'") # escape apostrophes
            insert_text += f'    <string name="{key}">{val}</string>\n'
        
        insert_text += "</resources>"
        
        new_content = content.replace("</resources>", insert_text)
        with open(file_path, "w", encoding="utf-8") as f:
            f.write(new_content)
        
        print(f"Updated {folder}/strings.xml")
    else:
        print(f"Warning: {file_path} does not exist.")

print("Chunk 1 translation complete.")
