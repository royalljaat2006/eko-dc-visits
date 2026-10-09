# CSP Visit Mobile App

> Converted from the uploaded working draft PDF into Markdown.


---

## Page 1

CSP Visit Mobile App 
Requirements & Feature Specification — Working Draft (Updated) 
Prepared for: Ganesh Kumar — Eko Bharat Ventures / Circle 1A85 
1. Overview 
This document defines the requirements for the CSP Visit Mobile App, covering two field-facing 
roles — District Coordinator (DC) and Circle Head — plus the access boundaries each has 
relative to Admin. It reflects the requirements as discussed, refined for clarity, with open 
questions and suggestions marked separately from confirmed decisions. 
Roles in scope: Admin, Circle Head, DC. Admin's own interface is assumed to be covered by 
the existing Eko DC Visits web dashboard (Overview / Visits / Attendance / CSP Workbench / 
Scorecard) unless stated otherwise. 
2. Role Hierarchy & Permissions 
Screen 
Feature 
Requirement 
(Refined) 
Priority 
Suggestion / 
Status 
All Roles 
Access hierarchy Admin: full 
access to every 
circle, DC, and 
CSP. Circle 
Head: 
view/edit/approv
e only for DCs 
and CSPs within 
their own circle. 
DC: submit/edit 
only their own 
attendance, 
visits, and 
CSP-detail 
requests for 
CSPs assigned 
to them. 
P0 
Needs one 
source-of-truth 
mapping table: 
Circle → Circle 
Head → DC → 
CSP. Every 
permission 
check in the app 
should read from 
this table, not be 
hardcoded per 
screen. 
3. DC Interface — Requirements 
Screen 
Feature 
Requirement 
(Refined) 
Priority 
Suggestion / 
Status 
Navigation 
Section order 
Move 
P0 
Disable the Visits


---

## Page 2

Screen 
Feature 
Requirement 
(Refined) 
Priority 
Suggestion / 
Status 
"Attendance" to 
be the first 
section, before 
"Visits" — DC 
must mark 
attendance 
before logging 
any visit. 
tab until 
attendance is 
marked, so 
ordering is 
enforced, not just 
visual. 
Attendance 
Check-in starts 
the day 
Marking 
attendance 
(Check-In) sets 
the day-start 
timestamp. 
Hours worked = 
Check-Out time 
− Check-In time. 
Distance 
travelled = GPS 
distance 
accumulated 
between 
Check-In and 
Check-Out. 
P0 
FINALIZED: 
Check-out is 
two-part — (a) 
manual "End 
Day" tap; (b) 
auto-checkout at 
a fixed cutoff 
(e.g. 9:00 PM 
IST) if forgotten, 
marked 
"Auto-closed — 
not confirmed by 
user" for Admin 
visibility. 
Attendance 
 
DCs, Circle 
Head home 
locations (lat 
long) for their 
attendance 
(Check-In, 
Check-out) 
P0 
Excel sheet for 
Lat Long 
Attendance 
No back-dated 
edits 
Attendance and 
all other records 
are fully locked 
for past dates — 
no edits, for DC, 
Circle Head, or 
Admin. Once a 
date has passed, 
its data is 
permanent. 
P0 
FINALIZED: fully 
locked, 
confirmed — no 
exceptions, no 
anomaly-flag or 
annotation path 
either. 
CSP Details 
New section — 
CSP-wise details 
Add a "CSP 
Details" section 
listing every CSP 
assigned to the 
P0 
Also show 
distance from 
current location 
and last-visit


---

## Page 3

Screen 
Feature 
Requirement 
(Refined) 
Priority 
Suggestion / 
Status 
DC: CSP Code, 
CSP Name, Full 
Address, 
Latitude, 
Longitude. 
date on each 
card, so the DC 
can prioritize 
which CSP to 
visit next. 
CSP Details 
CSP master data 
— already in-app 
(CSP 
Workbench) 
The CSP 
Workbench 
screen in the 
existing Eko DC 
Visits web 
dashboard 
already holds the 
full CSP master 
record. The 
DC-facing "CSP 
Details" list 
(Code, Name, 
Address, 
Lat/Long) should 
pull from this 
same master 
source — no 
separate data 
entry required. 
Full field 
template below. 
P0 
Confirm CSP 
Workbench's 
current columns 
match this 
template exactly; 
add any missing 
fields before 
wiring the DC 
mobile view to it. 
CSP Details 
Get Directions 
Each CSP entry 
has a "Get 
Directions" 
button. Tapping 
opens Google 
Maps navigation 
from the DC's 
current location 
to the CSP's 
lat/long. 
P0 
Use the 
universal link so 
it works on 
Android & iOS 
with no native 
SDK: 
https://www.goog
le.com/maps/dir/
?api=1&destinati
on=LAT,LONG 
CSP Details 
DC can edit, 
pending approval 
DC can edit a 
CSP's details 
one at a time. 
The edit is saved 
as a pending 
change request 
and does not go 
live until 
approved by the 
P0 
Approval screen 
should show old 
value vs 
proposed value 
side-by-side, 
plus an optional 
rejection reason 
sent back to the 
DC.


---

## Page 4

Screen 
Feature 
Requirement 
(Refined) 
Priority 
Suggestion / 
Status 
CSP's Circle 
Head or Admin. 
Scorecard 
Personal 
dashboard link 
Scorecard shows 
a "My 
Dashboard" link 
pointing only to 
the logged-in 
user's own 
dashboard URL 
(e.g. Munna 
Pathak sees only 
his link, never 
another DC's). 
P0 
Store the link as 
a field on each 
user's record 
(see roster 
below), not 
hardcoded per 
build, so it can 
be updated 
centrally. 
3.1 CSP Master Data Template (CSP Workbench) 
This is the field template the CSP Workbench screen (Admin web dashboard) should already 
hold, and which the DC app's "CSP Details" view should read from: 
C
S
P 
C
od
e 
C
S
P 
N
a
m
e 
G
en
de
r 
C
S
P 
M
ail 
ID 
M
ob
ile 
N
u
m
be
r 
Alt
er
na
tiv
e 
M
ob
ile 
N
u
m
be
r 
R
el
ati
on
sh
ip 
M
an
ag
er 
Di
str
ict 
Ci
rcl
e 
H
ea
d 
N
a
m
e 
A
O 
A
O 
E
m
ail 
ID 
Br
an
ch 
C
od
e 
Br
an
ch 
N
a
m
e 
Br
an
ch 
E
m
ail 
R
B
O 
R
B
O 
E
m
ail 
St
at
e 
Ci
rcl
e 
(L
H
O) 
L
H
O 
M
ail 
ID 
Po
pu
lat
io
n 
Fu
ll 
Ad
dr
es
s 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
4. Circle Head Interface — Requirements 
Screen 
Feature 
Requirement 
(Refined) 
Priority 
Suggestion / 
Status 
Attendance 
Attendance 
feature 
Circle Head 
needs the same 
check-in / 
check-out, hours, 
and distance 
P0 
Same open 
geofence 
question as DC 
attendance 
applies here.


---

## Page 5

Screen 
Feature 
Requirement 
(Refined) 
Priority 
Suggestion / 
Status 
flow as DC, for 
their own 
workday. 
CSP Details 
Bulk Excel 
upload 
Circle Head can 
upload an Excel 
file to 
bulk-update CSP 
address/details 
for CSPs within 
their own circle 
only. 
P1 
Add a 
preview/diff 
screen before 
committing the 
upload — a bad 
file could silently 
overwrite many 
CSPs at once. 
Approvals 
Approval queue 
for DC edits 
DC-submitted 
CSP-detail 
changes appear 
in the Circle 
Head's Approval 
Queue for 
approve/reject, 
scoped strictly to 
that Circle 
Head's own 
circle. 
P0 
— 
5. Finalized Decisions 
●​ Attendance check-out: manual "End Day" tap, with an automatic cutoff (e.g. 9:00 PM IST) 
as a safety net if the user forgets — auto-closed entries are flagged for Admin visibility. 
●​ Back-dated records: fully locked for everyone — DC, Circle Head, and Admin — with no 
edit, override, or annotation path once a date has passed. 
6. Open Questions (Need Decision 
Before Build) 
●​ Attendance geofence: must check-in happen within a defined radius of a start location, or 
can it be marked from anywhere? 
●​ Admin Interface scope: does Admin need any new screens in this mobile app, or does the 
existing web dashboard fully cover Admin's needs? 
7. Reference Data — DC Roster & 
Dashboard Links


---

## Page 6

Source for the Scorecard "My Dashboard" link (Section 3). Each row's link must be shown only 
to that DC when logged in. 

_[Redacted before publishing: the table of DC names, mobile numbers and per-DC dashboard links (7 rows). The real roster is personal data and lives in the gitignored `pilot-data/circle-1a85-roster.json`.]_
