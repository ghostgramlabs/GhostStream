import os

keys = [
    "home_connected_devices",
    "home_rename_device",
    "home_rename_device_subtitle",
    "home_rename_custom_name",
    "home_rename_save",
    "home_rename_cancel",
    "home_quick_filters",
    "home_quick_filter_today",
    "home_quick_filter_today_desc",
    "home_quick_filter_week",
    "home_quick_filter_week_desc",
    "home_quick_filter_month",
    "home_quick_filter_month_desc",
    "home_quick_filter_custom",
    "home_quick_filter_custom_desc",
    "home_date_range_title",
    "home_date_range_from",
    "home_date_range_start",
    "home_date_range_to",
    "home_date_range_end",
    "home_date_range_tip",
    "home_date_range_apply",
    "home_date_range_cancel",
    "home_saved_shares",
    "home_saved_shares_save_now",
    "home_saved_shares_open",
    "home_saved_shares_delete",
    "home_saved_shares_items_count",
    "library_date_group_label"
]

translations = {
    "values": ["Connected devices", "Rename device", "Device: %1$s • %2$s", "Custom name (optional)", "Save", "Cancel", "Quick filters", "Today", "Files from today", "Past week", "Files from the last 7 days", "Past month", "Files from the last 30 days", "Custom range", "Pick specific dates", "Select date range", "From date", "Select start date", "To date", "Select end date", "Tip: Open the library to filter by these dates", "Apply filter", "Cancel", "Saved shares", "Save now", "Open", "Delete", "%1$d items", "%1$s "],
    "values-ar": ["الأجهزة المتصلة", "إعادة تسمية الجهاز", "الجهاز: %1$s • %2$s", "اسم مخصص (اختياري)", "حفظ", "إلغاء", "عوامل تصفية سريعة", "اليوم", "ملفات اليوم", "الأسبوع الماضي", "ملفات آخر 7 أيام", "الشهر الماضي", "ملفات آخر 30 يومًا", "نطاق مخصص", "اختر تواريخ محددة", "حدد نطاق التاريخ", "من تاريخ", "حدد تاريخ البدء", "إلى تاريخ", "حدد تاريخ الانتهاء", "تلميح: افتح المكتبة للتصفية حسب هذه التواريخ", "تطبيق عامل التصفية", "إلغاء", "المشاركات المحفوظة", "حفظ الآن", "فتح", "حذف", "%1$d عناصر", "%1$s "],
    "values-de": ["Verbundene Geräte", "Gerät umbenennen", "Gerät: %1$s • %2$s", "Benutzerdefinierter Name (optional)", "Speichern", "Abbrechen", "Schnellfilter", "Heute", "Dateien von heute", "Letzte Woche", "Dateien der letzten 7 Tage", "Letzter Monat", "Dateien der letzten 30 Tage", "Benutzerdefinierter Bereich", "Bestimmte Daten auswählen", "Datumsbereich auswählen", "Von Datum", "Startdatum auswählen", "Bis Datum", "Enddatum auswählen", "Tipp: Öffne die Bibliothek, um nach diesen Daten zu filtern", "Filter anwenden", "Abbrechen", "Gespeicherte Freigaben", "Jetzt speichern", "Öffnen", "Löschen", "%1$d Elemente", "%1$s "],
    "values-es": ["Dispositivos conectados", "Renombrar dispositivo", "Dispositivo: %1$s • %2$s", "Nombre personalizado (opcional)", "Guardar", "Cancelar", "Filtros rápidos", "Hoy", "Archivos de hoy", "Semana pasada", "Archivos de los últimos 7 días", "Mes pasado", "Archivos de los últimos 30 días", "Rango personalizado", "Elegir fechas específicas", "Seleccionar rango de fechas", "Desde la fecha", "Seleccionar fecha de inicio", "Hasta la fecha", "Seleccionar fecha de finalización", "Consejo: Abre la biblioteca para filtrar por estas fechas", "Aplicar filtro", "Cancelar", "Recursos compartidos guardados", "Guardar ahora", "Abrir", "Eliminar", "%1$d elementos", "%1$s "],
    "values-fr": ["Appareils connectés", "Renommer l\'appareil", "Appareil : %1$s • %2$s", "Nom personnalisé (facultatif)", "Enregistrer", "Annuler", "Filtres rapides", "Aujourd\'hui", "Fichiers d\'aujourd\'hui", "Semaine dernière", "Fichiers des 7 derniers jours", "Mois dernier", "Fichiers des 30 derniers jours", "Plage personnalisée", "Choisissez des dates spécifiques", "Sélectionner la plage de dates", "À partir de", "Sélectionner la date de début", "Jusqu\'à", "Sélectionner la date de fin", "Conseil : Ouvrez la bibliothèque pour filtrer par ces dates", "Appliquer le filtre", "Annuler", "Partages enregistrés", "Enregistrer maintenant", "Ouvrir", "Supprimer", "%1$d éléments", "%1$s "],
    "values-hi": ["कनेक्टेड डिवाइस", "डिवाइस का नाम बदलें", "डिवाइस: %1$s • %2$s", "कस्टम नाम (वैकल्पिक)", "सहेजें", "रद्द करें", "त्वरित फ़िल्टर", "आज", "आज की फ़ाइलें", "पिछले सप्ताह", "पिछले 7 दिनों की फ़ाइलें", "पिछले महीने", "पिछले 30 दिनों की फ़ाइलें", "कस्टम रेंज", "विशिष्ट तिथियां चुनें", "दिनांक सीमा चुनें", "दिनांक से", "प्रारंभ दिनांक चुनें", "दिनांक तक", "समाप्ति दिनांक चुनें", "युक्ति: इन तिथियों के अनुसार फ़िल्टर करने के लिए लाइब्रेरी खोलें", "फ़िल्टर लागू करें", "रद्द करें", "सहेजे गए शेयर", "अभी सहेजें", "खोलें", "हटाएं", "%1$d आइटम", "%1$s "],
    "values-id": ["Perangkat terhubung", "Ganti nama perangkat", "Perangkat: %1$s • %2$s", "Nama kustom (opsional)", "Simpan", "Batal", "Filter cepat", "Hari Ini", "File dari hari ini", "Pekan lalu", "File 7 hari terakhir", "Bulan lalu", "File 30 hari terakhir", "Rentang kustom", "Pilih tanggal spesifik", "Pilih rentang tanggal", "Dari tanggal", "Pilih tanggal mulai", "Sampai tanggal", "Pilih tanggal akhir", "Tips: Buka perpustakaan untuk memfilter berdasarkan tanggal ini", "Terapkan filter", "Batal", "Bagikan tersimpan", "Simpan sekarang", "Buka", "Hapus", "%1$d item", "%1$s "],
    "values-it": ["Dispositivi connessi", "Rinomina dispositivo", "Dispositivo: %1$s • %2$s", "Nome personalizzato (opzionale)", "Salva", "Annulla", "Filtri rapidi", "Oggi", "File di oggi", "Settimana scorsa", "File degli ultimi 7 giorni", "Mese scorso", "File degli ultimi 30 giorni", "Intervallo personalizzato", "Scegli date specifiche", "Seleziona intervallo di date", "Dalla data", "Seleziona la data di inizio", "Alla data", "Seleziona la data di fine", "Suggerimento: Apri la libreria per filtrare in base a queste date", "Applica filtro", "Annulla", "Condivisioni salvate", "Salva ora", "Apri", "Elimina", "%1$d elementi", "%1$s "],
    "values-ja": ["接続中のデバイス", "デバイスの名前を変更", "デバイス: %1$s • %2$s", "カスタム名（オプション）", "保存", "キャンセル", "クイックフィルタ", "今日", "今日のファイル", "先週", "過去7日間のファイル", "先月", "過去30日間のファイル", "カスタム範囲", "特定の日付をピック", "日付範囲を選択", "開始日", "開始日を選択", "終了日", "終了日を選択", "ヒント: これらの日付でフィルタリングするにはライブラリを開きます", "フィルタを適用", "キャンセル", "保存された共有", "今すぐ保存", "開く", "削除", "%1$d アイテム", "%1$s "],
    "values-ko": ["연결된 기기", "기기 이름 바꾸기", "기기: %1$s • %2$s", "맞춤 이름 (선택사항)", "저장", "취소", "빠른 필터", "오늘", "오늘의 파일", "지난 주", "최근 7일 파일", "지난 달", "최근 30일 파일", "맞춤 범위", "특정 날짜 선택", "날짜 범위 선택", "시작일", "시작일 선택", "종료일", "종료일 선택", "팁: 이 날짜로 필터링하려면 라이브러리를 여세요", "필터 적용", "취소", "저장된 공유", "지금 저장", "열기", "삭제", "%1$d 항목", "%1$s "],
    "values-ml": ["കണക്‌റ്റുചെയ്‌ത ഉപകരണങ്ങൾ", "ഉപകരണത്തിന്റെ പേര് മാറ്റുക", "ഉപകരണം: %1$s • %2$s", "ഇഷ്‌ടാനുസൃത പേര് (ഓപ്ഷണൽ)", "സംരക്ഷിക്കുക", "റദ്ദാക്കുക", "ദ്രുത ഫിൽട്ടറുകൾ", "ഇന്ന്", "ഇന്നത്തെ ഫയലുകൾ", "കഴിഞ്ഞ ആഴ്ച", "കഴിഞ്ഞ 7 ദിവസത്തെ ഫയലുകൾ", "കഴിഞ്ഞ മാസം", "കഴിഞ്ഞ 30 ദിവസത്തെ ഫയലുകൾ", "ഇഷ്‌ടാനുസൃത ശ്രേണി", "നിർദ്ദിഷ്ട തീയതികൾ തിരഞ്ഞെടുക്കുക", "തീയതി ശ്രേണി തിരഞ്ഞെടുക്കുക", "തീയതി മുതൽ", "തുടങ്ങുന്ന തീയതി തിരഞ്ഞെടുക്കുക", "തീയതി വരെ", "അവസാന തീയതി തിരഞ്ഞെടുക്കുക", "നുറുങ്ങ്: ഈ തീയതികൾ പ്രകാരം ഫിൽട്ടർ ചെയ്യാൻ ലൈബ്രറി തുറക്കുക", "ഫിൽട്ടർ പ്രയോഗിക്കുക", "റദ്ദാക്കുക", "സംരക്ഷിച്ച ഷെയറുകൾ", "ഇപ്പോൾ സംരക്ഷിക്കുക", "തുറക്കുക", "ഇല്ലാതാക്കുക", "%1$d ഇനങ്ങൾ", "%1$s "],
    "values-nl": ["Verbonden apparaten", "Apparaat hernoemen", "Apparaat: %1$s • %2$s", "Aangepaste naam (optioneel)", "Opslaan", "Annuleren", "Snelle filters", "Vandaag", "Bestanden van vandaag", "Vorige week", "Bestanden van de afgelopen 7 dagen", "Vorige maand", "Bestanden van de afgelopen 30 dagen", "Aangepast bereik", "Kies specifieke datums", "Selecteer datumbereik", "Vanaf datum", "Selecteer startdatum", "Tot datum", "Selecteer einddatum", "Tip: Open de bibliotheek om op deze datums te filteren", "Filter toepassen", "Annuleren", "Opgeslagen shares", "Nu opslaan", "Openen", "Verwijderen", "%1$d items", "%1$s "],
    "values-pt": ["Dispositivos ligados", "Mudar o nome do dispositivo", "Dispositivo: %1$s • %2$s", "Nome personalizado (opcional)", "Guardar", "Cancelar", "Filtros rápidos", "Hoje", "Ficheiros de hoje", "Semana passada", "Ficheiros dos últimos 7 dias", "Mês passado", "Ficheiros dos últimos 30 dias", "Intervalo personalizado", "Escolha datas específicas", "Selecione o intervalo de datas", "Data de início", "Selecione a data de início", "Até à data", "Selecione a data de fim", "Dica: Abra a biblioteca para filtrar por estas datas", "Aplicar filtro", "Cancelar", "Partilhas guardadas", "Guardar agora", "Abrir", "Eliminar", "%1$d itens", "%1$s "],
    "values-ru": ["Подключенные устройства", "Переименовать устройство", "Устройство: %1$s • %2$s", "Пользовательское имя (дополнительно)", "Сохранить", "Отмена", "Быстрые фильтры", "Сегодня", "Файлы за сегодня", "Прошлая неделя", "Файлы за последние 7 дней", "Прошлый месяц", "Файлы за последние 30 дней", "Произвольный диапазон", "Выберите конкретные даты", "Выберите диапазон дат", "С даты", "Выберите дату начала", "По дату", "Выберите дату окончания", "Совет: Откройте библиотеку, чтобы отфильтровать по этим датам", "Применить фильтр", "Отмена", "Сохраненные общие доступы", "Сохранить сейчас", "Открыть", "Удалить", "%1$d элементов", "%1$s "],
    "values-ta": ["இணைக்கப்பட்ட சாதனங்கள்", "சாதனத்தின் மறுபெயரிடு", "சாதனம்: %1$s • %2$s", "தனிப்பயன் பெயர் (விருப்பத்திற்குரியது)", "சேமி", "ரத்துசெய்", "விரைவு வடிப்பான்கள்", "இன்று", "இன்றைய கோப்புகள்", "கடந்த வாரம்", "கடைசி 7 நாட்கள் கோப்புகள்", "கடந்த மாதம்", "கடைசி 30 நாட்கள் கோப்புகள்", "தனிப்பயன் வரம்பு", "குறிப்பிட்ட தேதிகளை தேர்வு செய்க", "தேதி வரம்பைத் தேர்ந்தெடுக்கவும்", "தேதியிலிருந்து", "தொடக்க தேதியைத் தேர்ந்தெடுக்கவும்", "தேதி வரை", "முடிவு தேதியைத் தேர்ந்தெடுக்கவும்", "உதவிக்குறிப்பு: இந்த தேதிகளின் அடிப்படையில் வடிகட்ட நூலகத்தைத் திறக்கவும்", "வடிப்பானைப் பயன்படுத்து", "ரத்துசெய்", "சேமிக்கப்பட்ட பகிர்வுகள்", "இப்போது சேமி", "திற", "அழி", "%1$d உருப்படிகள்", "%1$s "],
    "values-te": ["కనెక్ట్ చేయబడిన పరికరాలు", "పరికరం పేరు మార్చండి", "పరికరం: %1$s • %2$s", "అనుకూల పేరు (ఐచ్ఛికం)", "సేవ్ చేయండి", "రద్దు చేయండి", "శీఘ్ర ఫిల్టర్‌లు", "ఈ రోజు", "ఈ రోజు ఫైల్‌లు", "గత వారం", "గత 7 రోజుల ఫైల్‌లు", "గత నెల", "గత 30 రోజుల ఫైల్‌లు", "అనుకూల పరిధి", "నిర్దిష్ట తేదీలను ఎంచుకోండి", "తేదీ పరిధిని ఎంచుకోండి", "తేదీ నుండి", "ప్రారంభ తేదీని ఎంచుకోండి", "తేదీ వరకు", "ముగింపు తేదీని ఎంచుకోండి", "చిట్కా: ఈ తేదీల ద్వారా ఫిల్టర్ చేయడానికి లైబ్రరీని తెరవండి", "ఫిల్టర్ వర్తింపజేయండి", "రద్దు చేయండి", "సేవ్ చేయబడిన షేర్‌లు", "ఇప్పుడే సేవ్ చేయండి", "తెరవండి", "తొలగించండి", "%1$d అంశాలు", "%1$s "],
    "values-th": ["อุปกรณ์ที่เชื่อมต่อ", "เปลี่ยนชื่ออุปกรณ์", "อุปกรณ์: %1$s • %2$s", "ชื่อที่กำหนดเอง (ไม่บังคับ)", "บันทึก", "ยกเลิก", "ตัวกรองด่วน", "วันนี้", "ไฟล์จากวันนี้", "สัปดาห์ที่แล้ว", "ไฟล์จาก 7 วันที่ผ่านมา", "เดือนที่แล้ว", "ไฟล์จาก 30 วันที่ผ่านมา", "ช่วงที่กำหนดเอง", "เลือกวันที่กำหนด", "เลือกช่วงวันที่", "จากวันที่", "เลือกวันที่เริ่มต้น", "ถึงวันที่", "เลือกวันที่สิ้นสุด", "เคล็ดลับ: เปิดไลบรารีเพื่อกรองตามวันที่เหล่านี้", "ใช้ตัวกรอง", "ยกเลิก", "รายการแชร์ที่บันทึกไว้", "บันทึกตอนนี้", "เปิด", "ลบ", "%1$d รายการ", "%1$s "],
    "values-tr": ["Bağlı cihazlar", "Cihazı yeniden adlandır", "Cihaz: %1$s • %2$s", "Özel ad (isteğe bağlı)", "Kaydet", "İptal", "Hızlı filtreler", "Bugün", "Bugünün dosyaları", "Geçen hafta", "Son 7 günün dosyaları", "Geçen ay", "Son 30 günün dosyaları", "Özel aralık", "Belirli tarihleri seçin", "Tarih aralığı seçin", "Tarihinden", "Başlangıç tarihini seçin", "Tarihine kadar", "Bitiş tarihini seçin", "İpucu: Bu tarihlere göre filtrelemek için kitaplığı açın", "Filtre uygula", "İptal", "Kaydedilen paylaşımlar", "Şimdi kaydet", "Aç", "Sil", "%1$d öğe", "%1$s "],
    "values-vi": ["Thiết bị được kết nối", "Đổi tên thiết bị", "Thiết bị: %1$s • %2$s", "Tên tùy chỉnh (tùy chọn)", "Lưu", "Hủy", "Bộ lọc nhanh", "Hôm nay", "Tệp của ngày hôm nay", "Tuần trước", "Tệp từ 7 ngày qua", "Tháng trước", "Tệp từ 30 ngày qua", "Phạm vi tùy chỉnh", "Chọn ngày cụ thể", "Chọn phạm vi ngày", "Từ ngày", "Chọn ngày bắt đầu", "Đến ngày", "Chọn ngày kết thúc", "Mẹo: Mở thư viện để lọc theo các ngày này", "Áp dụng bộ lọc", "Hủy", "Tệp chia sẻ đã lưu", "Lưu ngay", "Mở", "Xóa", "%1$d mục", "%1$s "],
    "values-zh-rCN": ["已连接设备", "重命名设备", "设备：%1$s • %2$s", "自定义名称（可选）", "保存", "取消", "快速筛选", "今天", "今天的文件", "过去一周", "过去 7 天的文件", "过去一个月", "过去 30 天的文件", "自定义范围", "选择特定日期", "选择日期范围", "起始日期", "选择开始日期", "结束日期", "选择结束日期", "提示：打开库以按这些日期进行筛选", "应用筛选", "取消", "已保存的共享", "立即保存", "打开", "删除", "%1$d 个项目", "%1$s "],
    "values-zh-rTW": ["已連線裝置", "重新命名裝置", "裝置：%1$s • %2$s", "自訂名稱（選用）", "儲存", "取消", "快速篩選", "今天", "今天的檔案", "過去一週", "過去 7 天的檔案", "過去一個月", "過去 30 天的檔案", "自訂範圍", "選擇特定日期", "選擇日期範圍", "起始日期", "選擇開始日期", "結束日期", "選擇結束日期", "提示：開啟媒體庫以依這些日期進行篩選", "套用篩選", "取消", "已儲存的分享", "立即儲存", "開啟", "刪除", "%1$d 個項目", "%1$s "]
}

base_dir = r"C:\Users\sudhi\.gemini\antigravity\scratch\GhostStream\core\resources\src\main\res"

for folder, strings_list in translations.items():
    file_path = os.path.join(base_dir, folder, "strings.xml")
    if os.path.exists(file_path):
        with open(file_path, "r", encoding="utf-8") as f:
            content = f.read()
            
        if keys[0] in content:
            print(f"Skipping {folder}, already contains {keys[0]}")
            continue

        insert_text = "\n    <!-- Misc Native UI -->\n"
        for i, key in enumerate(keys):
            val = strings_list[i].replace("'", "\\'") 
            insert_text += f'    <string name="{key}">{val}</string>\n'
            
        insert_text += "</resources>"
        
        new_content = content.replace("</resources>", insert_text)
        with open(file_path, "w", encoding="utf-8") as f:
            f.write(new_content)
        
        print(f"Updated {folder}/strings.xml")

print("Chunk 4 translation complete.")
