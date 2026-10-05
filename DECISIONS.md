<div dir="rtl">

# DECISIONS

## 1. החלטות ארכיטקטוניות
- **Backend — הפרדת שכבות:** Controller (HTTP בלבד) ← Service (לוגיקה עסקית + `@Transactional`) ← Repository. חריגות דומיין (`NotFound/BadRequest/Conflict`) ממופות לקודי HTTP ב‑`GlobalExceptionHandler` אחד, כך שה‑controllers לא עוסקים בשגיאות.
- **בכוונה לא הוספתי:** mappers, ‏response DTOs ו‑interfaces ל‑services — בהיקף הזה זה over‑engineering. ה‑entities מוחזרים ישירות (עם `@JsonIgnoreProperties` הקיים לשבירת המעגליות); זה ויתור מתועד בסעיף 4.
- **Frontend:** שכבת services‏ (`LeaveRequestService`, `EmployeeService`), מודלים מטופסים עם enums שמשקפים את הסריאליזציה המספרית של ה‑backend, Reactive Form, ו‑`takeUntilDestroyed` לכל subscription — בלי memory leaks ובלי `any`.

## 2. הבאג ביתרת החופשה
- **מה ואיפה:** ב‑`create` חושבו הימים שכבר אושרו (`used`) אבל תנאי המכסה בדק רק `days > annualQuota` — כלומר כל בקשה שקטנה מהמכסה המלאה עברה, גם אם העובד כבר ניצל כמעט הכול.
- **תיקון:** `used + days > annualQuota` (כיום ב‑`LeaveRequestService.create`).
- **טסטים:** `create_ExceedingRemainingQuota_IsRejected` (18/20 מנוצלים + בקשת 5 ימים → 400), ובדיקת גבול `create_ExactlyRemainingQuota_Succeeds` (בדיוק היתרה → מאושר).

## 3. אישור בקשה (approve) ו‑concurrency
- בקשה לא קיימת → **404**; בקשה שכבר אושרה/נדחתה → **409**; אישור שיחרוג מהמכסה → **409** והבקשה נשארת PENDING.
- **מקביליות:** נעילה פסימית (`SELECT … FOR UPDATE`) בתוך הטרנזקציה — על שורת הבקשה (מונעת אישור כפול של אותה בקשה) ועל שורת העובד (שני אישורים לאותו עובד רצים בטור, כך שהשני רואה את הימים של הראשון ולא חורגים יחד מהמכסה). סדר נעילה קבוע (בקשה ← עובד) מונע deadlock.
- **טסט:** `approve_ConcurrentApprovals_CannotJointlyExceedQuota` — שני threads מאשרים במקביל שתי בקשות שכל אחת לבדה חוקית אך יחד חורגות; בדיוק אחת מצליחה (מול PostgreSQL אמיתי ב‑Testcontainers).
- **חלופה שנשקלה:** optimistic locking עם `@Version` — נדחתה כי הקונפליקט כאן הוא על ערך נגזר (סכום ימים של כמה שורות), לא על עדכון שורה בודדת.

## 4. על מה ויתרתי בגלל הזמן
- **Response DTOs** — מוחזרות entities; עם עוד יום הייתי מוסיפה DTO ייעודי ומנתקת את ה‑API מהמודל.
- **תחימת המכסה לשנה קלנדרית** — כמו ב‑POC, כל הבקשות המאושרות נספרות ללא תלות בשנה. הייתי תוחמת לשנה ומטפלת בבקשות חוצות‑שנים.
- **בקשות PENDING לא נספרות ביתרה בעת הגשה** — האכיפה הסופית היא ב‑approve (שם זה נעול ועקבי), אבל אפשר להחמיר כבר בהגשה.
- עוד: endpoint לדחייה, עימוד, אימות/הרשאות, כתובת API מבוססת environment בפרונט, בדיקות E2E.

## 5. שימוש ב‑AI
### איפה AI עזר (כולל prompts)
1. prompt: *"קרא את create ב‑LeaveRequestsController והסבר איך עובד יכול לחרוג מהמכסה השנתית"* → הצביע על כך ש‑`used` מחושב אך לא משתתף בתנאי. אימתתי מול הקוד וכתבתי קודם טסט רגרסיה שנכשל, ורק אז תיקנתי.
2. prompt: *"כתוב טסט JUnit שמריץ שני approve במקביל עם CyclicBarrier ו‑ExecutorService ומוודא שבדיוק אחד מצליח"* → קיבלתי שלד טוב; התאמתי אותו לקרוא לשירות ולאמת גם את סך הימים המאושרים ב‑DB.
3. debug סביבה: `mvn test` נכשל עם "Could not find a valid Docker environment" למרות ש‑Docker רץ. AI שיער שה‑docker-java שבתוך Testcontainers פונה ב‑API v1.32 ש‑Docker Engine 29 כבר לא תומך בו; אימתנו עם `curl` ‏(`/v1.32/info` ‏→ 400, ‏`/v1.44/info` ‏→ 200) והפתרון — קיבוע `api.version=1.44` ב‑surefire.

### איפה דחיתי/תיקנתי הצעה של AI
- הוצע להחליף `@Enumerated(ORDINAL)` ב‑`STRING` "כי זה עמיד יותר לשינוי סדר". דחיתי: זה שובר את הנתונים הקיימים ב‑DB (עמודות מספריות) ואת החוזה מול ה‑Angular client שעובד עם קודים מספריים. השינוי הנכון דורש מיגרציה מתואמת של DB ו‑client — לא במסגרת הזמן הזו.

### אבטחה
- **SQL Injection** ב‑`GET /api/leave-requests/search` ‏(`LeaveRequestsController`, השאילתה ה‑native המקורית): שם העובד שורשר ישירות לתוך SQL — קלט כמו `' OR '1'='1` חילץ את כל הרשומות, ואפשר היה להרחיב לחילוץ נתונים שרירותי. **תוקן** לשאילתה נגזרת של Spring Data עם פרמטרים קשורים, כולל טסט (`search_SqlInjectionPayload_ReturnsNoRowsInsteadOfLeakingAll`).

## 6. הוראות הרצה
- ללא שינוי מה‑README. הערה אחת: ב‑Docker Engine ‏29+ נדרש קיבוע `api.version` ל‑docker-java — כבר מוגדר ב‑`pom.xml` (surefire), כך ש‑`mvn test` עובד כרגיל.

</div>
