import os

keys = [
    "web_hero_eyebrow",
    "web_hero_title",
    "web_hero_desc1",
    "web_hero_desc2",
    "web_hero_desc3",
    "web_hero_stat_shared",
    "web_items",
    "web_hero_stat_ready",
    "web_hero_stat_access",
    "web_access_pin",
    "web_access_instant",
    "web_access_pin_desc",
    "web_access_instant_desc",
    "web_btn_videos",
    "web_btn_download_all_files",
    "web_cat_videos",
    "web_cat_photos",
    "web_cat_music",
    "web_cat_files",
    "web_recent_title",
    "web_recent_meta",
    "web_nav_home",
    "web_nav_drop_zone",
    "web_nav_logout",
    "web_strip_session",
    "web_strip_device",
    "web_strip_link",
    "web_security_pin",
    "web_security_open",
    "web_status_unknown"
]

translations = {
    "values": ["Private receiver view", "Stream &amp; share files offline", "Browse, play, preview", ", or download", " shared items on the same local network.", "Shared now", "items", "Ready on this local session", "Access", "PIN required", "Instant open", "Enter the code from the host phone", "Open in any browser on this network", "Browse videos", "Download all files", "Videos", "Photos", "Music", "Files", "Recently added", "highlighted items", "Home", "Drop Zone", "Log out", "Session", "Device", "Link", "PIN protected", "Open on local network", "Unknown"],
    "values-ar": ["عرض جهاز الاستقبال الخاص", "بث ومشاركة الملفات بلا اتصال", "تصفح، تشغيل، معاينة", "، أو تنزيل", " عناصر مشتركة على نفس الشبكة المحلية.", "مُشارك الآن", "عناصر", "جاهز في هذه الجلسة المحلية", "الوصول", "مطلوب PIN", "فتح فوري", "أدخل الرمز من الهاتف المضيف", "افتح في أي متصفح على هذه الشبكة", "تصفح مقاطع الفيديو", "تنزيل جميع الملفات", "مقاطع فيديو", "صور", "موسيقى", "ملفات", "المضافة حديثاً", "العناصر المميزة", "الرئيسية", "منطقة الإسقاط", "تسجيل الخروج", "جلسة", "الجهاز", "رابط", "محمي بـ PIN", "مفتوح على شبكة محلية", "غير معروف"],
    "values-de": ["Private Empfängeransicht", "Dateien offline streamen &amp; teilen", "Durchsuchen, abspielen, in der Vorschau anzeigen", ", oder herunterladen", " freigegebene Elemente im selben lokalen Netzwerk.", "Jetzt geteilt", "Elemente", "Bereit in dieser lokalen Sitzung", "Zugriff", "PIN erforderlich", "Sofort öffnen", "Geben Sie den Code vom Host-Telefon ein", "In einem beliebigen Browser in diesem Netzwerk öffnen", "Videos durchsuchen", "Alle Dateien herunterladen", "Videos", "Fotos", "Musik", "Dateien", "Kürzlich hinzugefügt", "Hervorgehobene Elemente", "Startseite", "Ablegebereich", "Abmelden", "Sitzung", "Gerät", "Link", "PIN-geschützt", "Im lokalen Netzwerk öffnen", "Unbekannt"],
    "values-es": ["Vista de receptor privado", "Transmite y comparte archivos sin conexión", "Explora, reproduce, previsualiza", ", o descarga", " elementos compartidos en la misma red local.", "Compartido ahora", "elementos", "Listo en esta sesión local", "Acceso", "Se requiere PIN", "Apertura instantánea", "Ingresa el código del teléfono anfitrión", "Abrir en cualquier navegador en esta red", "Explorar videos", "Descargar todos los archivos", "Videos", "Fotos", "Música", "Archivos", "Añadidos recientemente", "elementos destacados", "Inicio", "Zona de carga", "Cerrar sesión", "Sesión", "Dispositivo", "Enlace", "Protegido con PIN", "Abierto en red local", "Desconocido"],
    "values-fr": ["Vue du récepteur privé", "Diffusez et partagez des fichiers hors ligne", "Parcourez, lisez, prévisualisez", ", ou téléchargez", " éléments partagés sur le même réseau local.", "Partagé maintenant", "éléments", "Prêt sur cette session locale", "Accès", "Code PIN requis", "Ouverture instantanée", "Saisissez le code du téléphone hôte", "Ouvrir dans n\'importe quel navigateur de ce réseau", "Parcourir les vidéos", "Télécharger tous les fichiers", "Vidéos", "Photos", "Musique", "Fichiers", "Récemment ajoutés", "éléments mis en évidence", "Accueil", "Zone de dépôt", "Se déconnecter", "Session", "Appareil", "Lien", "Protégé par code PIN", "Ouvert sur réseau local", "Inconnu"],
    "values-hi": ["निजी रिसीवर दृश्य", "ऑफ़लाइन फ़ाइलें स्ट्रीम करें और साझा करें", "ब्राउज़ करें, चलाएं, पूर्वावलोकन करें", ", या डाउनलोड करें", " उसी स्थानीय नेटवर्क पर साझा किए गए आइटम।", "अभी साझा किया गया", "आइटम", "इस स्थानीय सत्र पर तैयार", "पहुंच", "पिन आवश्यक", "त्वरित ओपन", "होस्ट फ़ोन से कोड दर्ज करें", "इस नेटवर्क पर किसी भी ब्राउज़र में खोलें", "वीडियो ब्राउज़ करें", "सभी फ़ाइलें डाउनलोड करें", "वीडियो", "तस्वीरें", "संगीत", "फ़ाइलें", "हाल ही में जोड़ा गया", "हाइलाइट किए गए आइटम", "होम", "ड्रॉप ज़ोन", "लॉग आउट करें", "सत्र", "डिवाइस", "लिंक", "पिन से सुरक्षित", "स्थानीय नेटवर्क पर खोलें", "अज्ञात"],
    "values-id": ["Tampilan penerima pribadi", "Streaming &amp; bagikan file secara offline", "Jelajahi, putar, pratinjau", ", atau unduh", " item yang dibagikan di jaringan lokal yang sama.", "Dibagikan sekarang", "item", "Siap di sesi lokal ini", "Akses", "Diperlukan PIN", "Buka instan", "Masukkan kode dari ponsel host", "Buka di browser mana saja di jaringan ini", "Jelajahi video", "Unduh semua file", "Video", "Foto", "Musik", "File", "Baru ditambahkan", "item yang disorot", "Beranda", "Zona Taruh", "Keluar", "Sesi", "Perangkat", "Tautan", "Dilindungi PIN", "Buka di jaringan lokal", "Tidak diketahui"],
    "values-it": ["Vista ricevitore privato", "Trasmetti in streaming e condividi file offline", "Sfoglia, riproduci, visualizza in anteprima", ", o scarica", " elementi condivisi sulla stessa rete locale.", "Condivisi ora", "elementi", "Pronto in questa sessione locale", "Accesso", "PIN richiesto", "Apertura istantanea", "Inserisci il codice dal telefono host", "Apri in qualsiasi browser su questa rete", "Sfoglia i video", "Scarica tutti i file", "Video", "Foto", "Musica", "File", "Aggiunti di recente", "elementi in evidenza", "Home", "Zona di rilascio", "Esci", "Sessione", "Dispositivo", "Link", "Protetto da PIN", "Aperto su rete locale", "Sconosciuto"],
    "values-ja": ["プライベートレシーバービュー", "オフラインでファイルをストリーミング＆共有", "参照、再生、プレビュー", "、またはダウンロード", " 同じローカルネットワーク上の共有アイテム。", "現在共有中", "個のアイテム", "このローカルセッションで準備完了", "アクセス", "PINが必要です", "即時開く", "ホストの電話からのコードを入力してください", "このネットワーク上の任意のブラウザで開く", "ビデオを参照", "すべてのファイルをダウンロード", "ビデオ", "写真", "音楽", "ファイル", "最近追加された項目", "ハイライトされたアイテム", "ホーム", "ドロップゾーン", "ログアウト", "セッション", "デバイス", "リンク", "PIN保護", "ローカルネットワーク上で公開", "不明"],
    "values-ko": ["비공개 수신기 보기", "오프라인으로 파일 스트리밍 및 공유", "찾아보기, 재생, 미리보기", ", 또는 다운로드", " 동일한 로컬 네트워크에 공유된 항목.", "현재 공유 중", "개 항목", "이 로컬 세션에서 준비됨", "액세스", "PIN 필요", "즉시 열기", "호스트 전화의 코드를 입력하세요", "이 네트워크의 모든 브라우저에서 열기", "비디오 찾아보기", "모든 파일 다운로드", "비디오", "사진", "음악", "파일", "최근에 추가됨", "강조표시된 항목", "홈", "드롭 구역", "로그아웃", "세션", "기기", "링크", "PIN 보호", "로컬 네트워크에 열려 있음", "알 수 없음"],
    "values-ml": ["സ്വകാര്യ റിസീവർ വ്യൂ", "ഓഫ്‌ലൈനായി ഫയലുകൾ സ്ട്രീം ചെയ്യുക &amp; ഷെയർ ചെയ്യുക", "ബ്രൗസ് ചെയ്യുക, പ്ലേ ചെയ്യുക, പ്രിവ്യൂ കാണുക", ", അല്ലെങ്കിൽ ഡൗൺലോഡ് ചെയ്യുക", " ഒരേ ലോക്കൽ നെറ്റ്‌വർക്കിൽ ഷെയർ ചെയ്ത ഇനങ്ങൾ.", "ഇപ്പോൾ ഷെയർ ചെയ്തു", "ഇനങ്ങൾ", "ഈ ലോക്കൽ സെഷനിൽ തയ്യാറാണ്", "ആക്സസ്", "PIN ആവശ്യമാണ്", "ഉടൻ തുറക്കുക", "ഹോസ്റ്റ് ഫോണിൽ നിന്നുള്ള കോഡ് നൽകുക", "ഈ നെറ്റ്‌വർക്കിലെ ഏത് ബ്രൗസറിലും തുറക്കുക", "വീഡിയോകൾ ബ്രൗസ് ചെയ്യുക", "എല്ലാ ഫയലുകളും ഡൗൺലോഡ് ചെയ്യുക", "വീഡിയോകൾ", "ഫോട്ടോകൾ", "സംഗീതം", "ഫയലുകൾ", "സമീപകാലത്ത് ചേർത്തവ", "ഹൈലൈറ്റ് ചെയ്ത ഇനങ്ങൾ", "ഹോം", "ഡ്രോപ്പ് സോൺ", "ലോഗ് ഔട്ട് ചെയ്യുക", "സെഷൻ", "ഉപകരണം", "ലിങ്ക്", "PIN പരിരക്ഷിച്ചിരിക്കുന്നു", "ലോക്കൽ നെറ്റ്‌വർക്കിൽ തുറക്കുക", "അജ്ഞാതം"],
    "values-nl": ["Privé-ontvangerweergave", "Bestanden offline streamen en delen", "Bladeren, afspelen, bekijken", ", of downloaden", " gedeelde items op hetzelfde lokale netwerk.", "Nu gedeeld", "items", "Klaar op deze lokale sessie", "Toegang", "PIN vereist", "Direct openen", "Voer de code van de hosttelefoon in", "Open in een browser op dit netwerk", "Bladeren door video\'s", "Alle bestanden downloaden", "Video\'s", "Foto\'s", "Muziek", "Bestanden", "Recent toegevoegd", "gemarkeerde items", "Startpagina", "Neerzetzone", "Uitloggen", "Sessie", "Apparaat", "Link", "PIN-beveiligd", "Openen op lokaal netwerk", "Onbekend"],
    "values-pt": ["Vista de recetor privado", "Transmitir e partilhar ficheiros offline", "Percorrer, reproduzir, pré-visualizar", ", ou transferir", " itens partilhados na mesma rede local.", "Partilhado agora", "itens", "Pronto nesta sessão local", "Acesso", "PIN necessário", "Abertura instantânea", "Introduza o código do telefone anfitrião", "Abrir num navegador nesta rede", "Procurar vídeos", "Transferir todos os ficheiros", "Vídeos", "Fotografias", "Música", "Ficheiros", "Adicionado recentemente", "itens destacados", "Início", "Zona de largada", "Terminar sessão", "Sessão", "Dispositivo", "Ligação", "Protegido por PIN", "Aberto na rede local", "Desconhecido"],
    "values-ru": ["Вид частного приемника", "Стрим и обмен файлами в автономном режиме", "Просмотр, воспроизведение, предпросмотр", ", или загрузка", " общих элементов в одной тактильной сети.", "Доступно сейчас", "элементов", "Готово в этом локальном сеансе", "Доступ", "Требуется PIN", "Мгновенное открытие", "Введите код с главного телефона", "Откройте в любом браузере в этой сети", "Обзор видео", "Скачать все файлы", "Видео", "Фото", "Музыка", "Файлы", "Недавно добавленные", "выделенных элементов", "Главная", "Зона загрузки", "Выйти", "Сеанс", "Устройство", "Ссылка", "Защищено PIN", "Открыто в локальной сети", "Неизвестно"],
    "values-ta": ["தனிப்பட்ட ரிசீவர் பார்வை", "ஆஃப்லைனில் கோப்புகளை ஸ்ட்ரீம் மற்றும் பகிர்", "உலாவு, இயக்கு, முன்காட்சி", ", அல்லது பதிவிறக்கு", " அதே உள்ளூர் நெட்வொர்க்கில் பகிரப்பட்ட உருப்படிகள்.", "இப்போது பகிரப்பட்டது", "உருப்படிகள்", "இந்த உள்ளூர் அமர்வில் தயாராக உள்ளது", "அணுகல்", "PIN தேவை", "உடன் திற", "ஹோஸ்ட் போனிலிருந்து குறியீட்டை உள்ளிடு", "இந்த நெட்வொர்க்கில் ஏதேனும் உலாவி திறக்கவும்", "வீடியோக்களை உலாவு", "எல்லா கோப்புகளையும் பதிவிறக்கு", "வீடியோக்கள்", "புகைப்படங்கள்", "இசை", "கோப்புகள்", "சமீபத்தில் சேர்த்தவை", "சிறப்பம்ச உருப்படிகள்", "முகப்பு", "டிராப் சோன்", "வெளியேறு", "அமர்வு", "சாதனம்", "இணைப்பு", "PIN பாதுகாக்கலானது", "உள்ளூர் நெட்வொர்க்கில் திற", "தெரியவில்லை"],
    "values-te": ["ప్రైవేట్ రిసీవర్ వ్యూ", "ఆఫ్‌లైన్‌లో ఫైల్‌లను ప్రసారం చేయండి మరియు షేర్ చేయండి", "బ్రౌజ్ చేయండి, ప్లే చేయండి, ప్రివ్యూ చూడండి", ", లేదా డౌన్‌లోడ్ చేయండి", " అదే స్థానిక నెట్‌వర్క్‌లో పంచుకున్న అంశాలు.", "ఇప్పుడు షేర్ చేయబడింది", "అంశాలు", "ఈ స్థానిక సెషన్‌లో సిద్ధంగా ఉంది", "యాక్సెస్", "PIN అవసరం", "తక్షణం తెరవండి", "హోస్ట్ ఫోన్ నుండి కోడ్‌ను నమోదు చేయండి", "ఈ నెట్‌వర్క్‌లోని ఏ బ్రౌజర్‌లోనైనా తెరవండి", "వీడియోలను బ్రౌజ్ చేయండి", "అన్ని ఫైల్‌లను డౌన్‌లోడ్ చేయండి", "వీడియోలు", "ఫోటోలు", "సంగీతం", "ఫైల్‌లు", "ఇటీవల జోడించబడినవి", "హైలైట్ చేసిన అంశాలు", "హోమ్", "డ్రాప్ జోన్", "లాగ్ అవుట్ చేయండి", "సెషన్", "పరికరం", "లింక్", "PIN రక్షించబడింది", "స్థానిక నెట్‌వర్క్‌లో తెరవబడింది", "తెలియదు"],
    "values-th": ["มุมมองผู้รับส่วนตัว", "สตรีม &amp; แชร์ไฟล์แบบออฟไลน์", "เรียกดู เริ่มเล่น ดูตัวอย่าง", ", หรือดาวน์โหลด", " รายการที่แชร์บนเครือข่ายท้องถิ่นเดียวกัน", "แชร์อยู่ตอนนี้", "รายการ", "พร้อมใช้งานในเซสชันท้องถิ่นนี้", "การเข้าถึง", "ต้องใช้ PIN", "เปิดได้ทันที", "ป้อนรหัสจากโทรศัพท์โฮสต์", "เปิดในเบราว์เซอร์ใดก็ได้บนเครือข่ายนี้", "เรียกดูวิดีโอ", "ดาวน์โหลดไฟล์ทั้งหมด", "วิดีโอ", "รูปภาพ", "เพลง", "ไฟล์", "เพิ่มเมื่อเร็วๆ นี้", "รายการเด่น", "หน้าแรก", "พื้นที่วางไฟล์", "ออกจากระบบ", "เซสชัน", "อุปกรณ์", "ลิงก์", "ได้รับการปกป้องด้วย PIN", "เปิดแชร์บนเครือข่ายท้องถิ่น", "ไม่ทราบ"],
    "values-tr": ["Özel alıcı görünümü", "Çevrimdışı dosyaları yayınla ve paylaş", "Göz at, oynat, önizle", ", veya indir", " aynı yerel ağda paylaşılan öğeler.", "Şimdi paylaşılıyor", "öğeler", "Bu yerel oturumda hazır", "Erişim", "PIN gerekli", "Anında açılış", "Ana telefondaki kodu girin", "Bu ağdaki herhangi bir tarayıcıda aç", "Videolara göz at", "Tüm dosyaları indir", "Videolar", "Fotoğraflar", "Müzik", "Dosyalar", "Son eklenenler", "vurgulanan öğeler", "Ana Sayfa", "Bırakma Alanı", "Çıkış Yap", "Oturum", "Cihaz", "Bağlantı", "PIN korumalı", "Yerel ağa açık", "Bilinmiyor"],
    "values-vi": ["Chế độ xem bộ thu riêng tư", "Phát trực tuyến &amp; chia sẻ tệp ngoại tuyến", "Duyệt, phát, xem trước", ", hoặc tải xuống", " các tệp được chia sẻ trên cùng mạng cục bộ.", "Chia sẻ bây giờ", "mục", "Sẵn sàng ở phiên cục bộ này", "Truy cập", "Yêu cầu mã PIN", "Mở tức thì", "Nhập mã từ điện thoại lưu trữ", "Mở bằng trình duyệt bất kỳ trên mạng này", "Duyệt video", "Tải tất cả các tệp", "Video", "Ảnh", "Nhạc", "Tệp", "Đã thêm gần đây", "các mục nổi bật", "Trang chủ", "Khu vực thả", "Đăng xuất", "Phiên", "Thiết bị", "Liên kết", "Được bảo vệ bằng mã PIN", "Mở trên mạng cục bộ", "Không xác định"],
    "values-zh-rCN": ["私密接收者视图", "离线流式传输与共享文件", "浏览、播放、预览", "，或下载", " 处于同一本地网络上的共享项目。", "现已共享", "个项目", "在此本地会话中就绪", "访问权限", "需要 PIN", "即时打开", "输入来自主机手机的代码", "在此网络上的任意浏览器中打开", "浏览视频", "下载所有文件", "视频", "照片", "音乐", "文件", "最近添加", "个突出显示的项目", "主页", "拖放区", "退出登录", "会话", "设备", "链接", "支持 PIN 保护", "在本地网络中打开", "未知"],
    "values-zh-rTW": ["私密接收者檢視", "離線串流與分享檔案", "瀏覽、播放、預覽", "，或下載", " 位於相同區域網路上的分享項目。", "現已分享", "個項目", "在此本機工作階段就緒", "存取權限", "需要 PIN 碼", "立即開啟", "輸入來自管理員手機的代碼", "在此網路中的任何瀏覽器中開啟", "瀏覽影片", "下載所有檔案", "影片", "相片", "音樂", "檔案", "最近新增", "個醒目顯示項目", "首頁", "拖放區", "登出", "工作階段", "裝置", "連結", "受到 PIN 碼保護", "於區域網路開放", "未知"]
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

        insert_text = "\n    <!-- Web UI -->\n"
        for i, key in enumerate(keys):
            val = strings_list[i].replace("'", "\\'") 
            insert_text += f'    <string name="{key}">{val}</string>\n'
        
        insert_text += "</resources>"
        
        new_content = content.replace("</resources>", insert_text)
        with open(file_path, "w", encoding="utf-8") as f:
            f.write(new_content)
        
        print(f"Updated {folder}/strings.xml")

print("Chunk 3 translation complete.")
