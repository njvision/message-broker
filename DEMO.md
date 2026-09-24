# Cum se simulează transmiterea și cum se verifică primirea mesajelor

Ghid pas cu pas pentru demonstrarea lucrării. Fiecare scenariu spune **ce
demonstrează**, **ce comenzi se dau** și **ce trebuie să vezi**.

Comenzile sunt scrise pentru `bash` (Git Bash pe Windows). Pentru PowerShell
vezi [secțiunea 5](#5-aceleași-comenzi-în-powershell) — `curl` din PowerShell 5.1
strică ghilimelele din JSON.

> Aceleași scenarii există și ca **colecție Postman**, cu verificările automate:
> [`postman/`](postman/). Dacă preferi un client grafic sau vrei să rulezi toate
> verificările dintr-o comandă, folosește-o pe aceea.

---

## 0. Cine cu cine vorbește

```
                            ┌──▶ r1  (grup workers)   :9101
sender  ──▶  broker (agent) ├──▶ r2  (grup workers)   :9102
 :9200         :8080        └──▶ r3  (grup reports)   :9103
```

Fiecare dreptunghi este un **proces separat**, în containerul lui.

**Un singur emițător**, care alege destinația la fiecare trimitere:

| Scris ca | Înseamnă | Cine primește |
|---|---|---|
| `workers` | grupul `workers` | **unul** dintre receptorii lui, ales de agent |
| `every:workers` | toți din `workers` | **toți** receptorii acelui grup, și nimeni altcineva |
| `@r2` | receptorul `r2` | **numai** `r2` |
| `all` | difuzare | **un membru din fiecare grup** |
| `every:all` | chiar toți | **fiecare receptor care există** |

Două întrebări decid destinația — *un grup sau toate* și *un membru sau toți*:

|  | un membru | toți membrii |
|---|---|---|
| **un grup** | `workers` | `every:workers` |
| **toate grupurile** | `all` | `every:all` |

Cu `r1` și `r2` în `workers` și `r3` în `reports`, măsurat pe sistemul pornit:

| Destinație | r1 | r2 | r3 |
|---|---|---|---|
| `workers` | unul din ei | unul din ei | — |
| `every:workers` | ✓ | ✓ | — |
| `all` | unul din ei | unul din ei | ✓ |
| `every:all` | ✓ | ✓ | ✓ |

**`all` e cel care surprinde:** dă o copie *fiecărui grup*, dar în interiorul
grupului tot un singur receptor o ia. Ca să ajungă chiar la toți, folosește
`every:all`.

Emițătorul nu știe câți receptori există, nici unde sunt — doar un nume.

**Niciun grup nu e configurat în agent.** `workers` și `reports` există pentru că
receptorii s-au înregistrat spunând din ce grup fac parte. Schimbi
`RECEIVER_GROUP` în `docker-compose.yml` și topologia se schimbă cu ea.

| Serviciu | Port | Rol |
|---|---|---|
| `rabbitmq` | 15672 | UI de administrare (guest/guest) |
| `broker` | 8080 | agentul de mesaje |
| `sender` | 9200 | emițătorul |
| `receiver-1` | 9101 | receptorul `r1`, grup `workers` |
| `receiver-2` | 9102 | receptorul `r2`, grup `workers` |
| `receiver-3` | 9103 | receptorul `r3`, grup `reports` |
| `receiver-4` | 9104 | receptorul `r4`, grup `archive` — pornit doar la scenariul 8 |

---

## 1. Pornirea

Este nevoie de **JDK 21+** și de Docker pornit.

```bash
export JAVA_HOME="C:\Users\<user>\.jdks\corretto-21.0.4"   # dacă JAVA_HOME e pe 17
./mvnw -DskipTests package
docker compose up -d --build
```

## 2. Verifică întâi că totul e viu

```bash
docker compose ps
```

Toate cele 6 servicii trebuie să fie `Up ... (healthy)`.

```bash
curl http://localhost:8080/actuator/health        # include starea RabbitMQ
curl http://localhost:9200/targets                # ce poate adresa emițătorul
```

`/targets` trebuie să arate două grupuri și trei receptori:

```json
{ "broadcast": "all",
  "groups": [ "reports", "workers" ],
  "receivers": [
    { "name": "r1", "group": "workers", "addressAs": "@r1" },
    { "name": "r2", "group": "workers", "addressAs": "@r2" },
    { "name": "r3", "group": "reports", "addressAs": "@r3" } ] }
```

Asta e deja o verificare, și cea mai importantă: **lista nu vine din nicio
configurație a agentului**. Receptorii s-au înregistrat singuri la pornire,
fiecare cu numele și grupul lui, iar grupurile au apărut pentru că ei le-au
numit.

---

## 3. Scenarii

### Scenariul 1 — către un grup

**Demonstrează:** obiectivul 1.c, structura unul-la-unu pe grupuri.

```bash
curl -X POST 'http://localhost:9200/send/group/workers?content=pentru oricine din workers'
```

Răspunsul spune unde a fost trimis:

```json
{ "target": "workers", "accepted": true, "addressedTo": "group 'workers'" }
```

**Verifică primirea** — la aplicațiile-receptor, nu la agent:

```bash
curl http://localhost:9101/received     # r1
curl http://localhost:9102/received     # r2
curl http://localhost:9103/received     # r3
```

**Ce trebuie să vezi:** textul la **unul** dintre `r1`/`r2`, la **niciunul** dintre
ele la `r3`.

---

### Scenariul 2 — către un singur receptor

**Demonstrează:** adresarea directă — capabilitatea care deosebește „trimite
grupului" de „trimite lui".

```bash
curl -X POST 'http://localhost:9200/send/to/r1?content=doar pentru r1'
```

Sau, echivalent, direct la agent:

```bash
curl -X POST http://localhost:8080/api/messages \
  -H 'Content-Type: application/json' \
  -d '{"sender":"alice","content":"doar pentru r1","target":"@r1"}'
```

Răspunsul agentului arată ceva important:

```json
{ "addressedTo": "receiver 'r1'", "routingKey": "messager.workers" }
```

Mesajul tot prin coada grupului `workers` trece — **nu există o coadă privată
pentru fiecare receptor**. Ce îl face să ajungă la `r1` și numai la el e
destinatarul purtat alături de mesaj, pe care pasul de livrare îl respectă în loc
să aleagă singur.

**Verifică:**

```bash
curl http://localhost:9101/received | grep "doar pentru r1"    # este
curl http://localhost:9102/received | grep "doar pentru r1"    # nu este
```

`r2` e în același grup, pe aceeași coadă, și tot nu îl primește.

---

### Scenariul 3 — contrastul, în cifre

**Demonstrează:** aceiași receptori, două comportamente, după cum a fost adresat
mesajul.

Notează contoarele, trimite 4 mesaje **direct lui r1**, compară:

```bash
curl -s http://localhost:9101/received | grep -o '"total":[0-9]*'
curl -s http://localhost:9102/received | grep -o '"total":[0-9]*'

for i in 1 2 3 4; do
  curl -s -X POST "http://localhost:9200/send/to/r1?content=direct $i" > /dev/null
done
sleep 3

curl -s http://localhost:9101/received | grep -o '"total":[0-9]*'
curl -s http://localhost:9102/received | grep -o '"total":[0-9]*'
```

**Ce trebuie să vezi:** `r1` crește cu **4**, `r2` cu **0**.

Acum aceleași 4 mesaje, dar **către grup**:

```bash
for i in 1 2 3 4; do
  curl -s -X POST "http://localhost:9200/send/group/workers?content=grup $i" > /dev/null
done
sleep 3

curl -s http://localhost:9101/received | grep -o '"total":[0-9]*'
curl -s http://localhost:9102/received | grep -o '"total":[0-9]*'
```

**Ce trebuie să vezi:** creșterile adunate dau **exact 4**, împărțite între cei
doi. Rulare reală:

```
direct:  r1 +4,  r2 +0
pe grup: r1 +2,  r2 +2      (total 4 — niciun mesaj dublat, niciunul pierdut)
```

Contoarele proprii ale receptorului arată și de ce:

```bash
curl -s http://localhost:9101/received | grep -o '"directlyAddressed":[0-9]*'
```

Agentul îi spune fiecărui receptor, la livrare, în ce calitate primește mesajul.

---

### Scenariul 3b — către toți membrii unui grup

**Demonstrează:** difuzare în interiorul unui grup — `every:` — și diferența
față de celelalte două forme de plural.

Situația: sunt **3 receptori**, dintre care **2** în grupul `workers`. Vrem ca
mesajul să ajungă la amândoi cei din `workers`, dar nu și la `r3`.

```bash
curl -X POST 'http://localhost:9200/send/everyone/workers?content=pentru toti din workers'
```

sau, echivalent, direct la agent:

```bash
curl -X POST http://localhost:8080/api/messages \
  -H 'Content-Type: application/json' \
  -d '{"sender":"alice","content":"pentru toti din workers","target":"every:workers"}'
```

Agentul confirmă: `"addressedTo": "every member of group 'workers'"`.

**Verifică:**

```bash
for p in 9101 9102 9103; do curl -s http://localhost:$p/received | grep -o '"total":[0-9]*'; done
```

**Ce trebuie să vezi** — rulare reală, pornind de la zero:

```
           r1 r2 r3
inainte:    0  0  0
dupa:       1  1  0
```

Amândoi din `workers` au primit câte o copie; `r3`, din alt grup, nu a primit
nimic. Compară cu celelalte forme, măsurate la rând pe același sistem:

| Trimitere | r1 | r2 | r3 |
|---|---|---|---|
| `send/group/workers` | +0 | +1 | +0 |
| `send/everyone/workers` | +1 | +1 | +0 |
| `send/to/r1` | +1 | +0 | +0 |
| `send?target=all` | +1 | +0 | +1 |
| `send?target=every:all` | +1 | +1 | +1 |

Mecanic, `every:workers` pune **un singur** mesaj în coada grupului, exact ca o
trimitere obișnuită — nu există o a doua coadă pentru difuzare. Ce îl transformă
din „unul dintre ei" în „toți" este un indicator purtat în antetul mesajului, pe
care pasul de livrare îl citește. Fiecare membru primește apoi propria copie prin
HTTP.

Dacă unul dintre membri nu răspunde, ceilalți primesc mesajul oricum, iar o copie
ajunge în scrisorile nelivrate menționând exact cine a ratat-o — nu se comută pe
altcineva, fiindcă toți erau deja destinatari.

---

### Scenariul 4 — către toți

**Demonstrează:** obiectivul 1.c, structura unul-la-mulți (Publish-Subscribe).

```bash
curl -X POST 'http://localhost:9200/send?target=all&content=anunt pentru toti'
```

**Verifică:**

```bash
curl http://localhost:9103/received | grep "anunt pentru toti"     # reports
curl http://localhost:9101/received | grep "anunt pentru toti"
curl http://localhost:9102/received | grep "anunt pentru toti"
```

**Ce trebuie să vezi:** textul la `r3` **și** la unul dintre `r1`/`r2` — fiecare
grup primește exact o copie, iar în interiorul grupului tot un singur receptor o
tratează.

> **Aici apare confuzia cea mai frecventă.** Dacă trimiți `all` și vezi că doar
> `r2` și `r3` au primit, iar `r1` nu, **nu e o defecțiune** — a fost rândul lui
> `r2` în grupul `workers`. Trimite încă o difuzare și o va lua `r1`. Verificat
> pe sistem, cu 4 difuzări la rând: `r1 +2, r2 +2, r3 +4`.

### Scenariul 4b — chiar către toți, fără excepție

Dacă vrei ca **fiecare receptor care există** să primească mesajul, inclusiv
amândoi din `workers`, folosește `every:all`:

```bash
curl -X POST 'http://localhost:9200/send?target=every:all&content=chiar pentru toti'
```

Agentul confirmă: `"addressedTo": "every receiver of every group"`.

**Ce trebuie să vezi** — rulare reală, pornind de la zero:

```
           r1 r2 r3
inainte:    0  0  0
dupa:       1  1  1
```

Mecanic: mesajul se publică o singură dată, cu aceeași cheie ca difuzarea
obișnuită, deci fiecare grup primește o copie în coada lui — dar indicatorul
„toți membrii" călătorește cu el, așa că fiecare grup o împarte mai departe la
toți receptorii săi, în loc să o dea unuia singur.

Rezumatul celor patru forme, măsurate la rând pe același sistem:

| Trimitere | r1 | r2 | r3 |
|---|---|---|---|
| `target=workers` | +0 | +1 | +0 |
| `target=every:workers` | +1 | +1 | +0 |
| `target=all` | +1 | +0 | +1 |
| `target=every:all` | +1 | +1 | +1 |

---

### Scenariul 5 — mesaj invalid și destinatar inexistent

**Demonstrează:** obiectivul 1.d, validarea la punctul de intrare.

```bash
curl -X POST http://localhost:8080/api/messages \
  -H 'Content-Type: application/json' \
  -d '{"sender":"","content":"","target":"workers"}'
```

```json
{ "status": 400, "title": "Invalid message",
  "violations": [ "content an empty message is not worth routing",
                  "sender must be given, so the receiver knows who sent this" ] }
```

Un receptor care nu există:

```bash
curl -X POST http://localhost:8080/api/messages \
  -H 'Content-Type: application/json' \
  -d '{"sender":"bob","content":"salut","target":"@nimeni"}'
```

Tot `400` — stratul de rutare verifică destinația **înainte** de publicare, deci
mesajul nu ajunge niciodată pe exchange. La fel pentru un grup inexistent.

---

### Scenariul 6 — receptor căzut: comutare sau scrisori nelivrate

**Demonstrează:** obiectivul 1.d, și diferența de politică dintre cele două
feluri de adresare.

Receptorii au un comutator care îi face să refuze mesajele, ca să nu fie nevoie
să oprești containerul:

```bash
curl -X POST http://localhost:9101/fail        # r1 refuză tot
```

**Adresat grupului — comută:**

```bash
curl -X POST 'http://localhost:9200/send/group/workers?content=oricine din grup'
sleep 5
curl http://localhost:9102/received | grep "oricine din grup"     # a ajuns la r2
```

```
Attempt 1/3 to 'r1' (http://receiver-1:9100/receive) failed: ServiceUnavailable: 503 ...
Attempt 2/3 to 'r1' ... failed
Attempt 3/3 to 'r1' ... failed
Message <id> delivered to receiver 'r2' of group 'workers'
```

**Adresat lui @r1 — nu comută:**

```bash
curl -X POST 'http://localhost:9200/send/to/r1?content=numai r1 avea voie'
sleep 6
curl http://localhost:9102/received | grep "numai r1 avea voie"    # NU e acolo
curl http://localhost:8080/api/messages/dead-letters               # e aici
```

**Ce trebuie să vezi:** același receptor căzut, dar mesajul adresat pe nume nu e
dat altcuiva — ar fi contrariul a ce a cerut emițătorul. Ajunge în canalul
scrisorilor nelivrate, cu motivul atașat.

Repară-l:

```bash
curl -X POST http://localhost:9101/heal
```

---

### Scenariul 7 — tot grupul căzut

**Demonstrează:** Dead Letter Channel pentru mesajele adresate unui grup.

```bash
curl -X POST http://localhost:9101/fail
curl -X POST http://localhost:9102/fail

curl -X POST 'http://localhost:9200/send/group/workers?content=nimeni nu poate lua asta'
sleep 8

curl http://localhost:8080/api/messages/dead-letters
docker compose logs broker | grep "dead letter channel" | tail -3
```

**Ce trebuie să vezi:** logul spune de ce, cu ambii receptori și eroarea fiecăruia:

```
Message <id> could not be delivered to any receiver of group 'workers':
  [r1 (http://receiver-1:9100/receive) -> ServiceUnavailable: 503 ...,
   r2 (http://receiver-2:9100/receive) -> ServiceUnavailable: 503 ...]
```

Repară-i:

```bash
curl -X POST http://localhost:9101/heal
curl -X POST http://localhost:9102/heal
```

---

### Scenariul 8 — un participant nou, într-un grup care nu exista

**Demonstrează:** obiectivul 1.b, numărul de canale este variabil — și că
topologia urmează participanții, nu configurația.

```bash
docker compose --profile extra up -d receiver-4
```

Atât. Verifică:

```bash
curl http://localhost:9200/targets
```

**Ce trebuie să vezi:** grupul `archive` și receptorul `@r4` au apărut, deși
nimeni nu i-a declarat nicăieri:

```json
{ "groups": [ "archive", "reports", "workers" ],
  "receivers": [ ..., { "name": "r4", "group": "archive", "addressAs": "@r4" } ] }
```

Trimite-i:

```bash
curl -X POST 'http://localhost:9200/send/to/r4?content=salut archive'
sleep 2
curl http://localhost:9104/received
```

Coada, legăturile și consumatorii au apărut în timpul funcționării, iar emițătorul
a putut adresa imediat o destinație care nu exista acum un minut — fără nicio
repornire și fără nicio schimbare în configurația lui.

Scoate-l la loc:

```bash
docker compose --profile extra stop receiver-4
curl -X DELETE http://localhost:8080/api/receivers/r4
curl -X DELETE 'http://localhost:8080/api/groups/archive?deleteQueue=true'
```

---

### Scenariul 9 — agentul cade: emițătorul află

**Demonstrează:** obiectivul 1.d, căderea agentului.

```bash
docker compose stop rabbitmq

curl -X POST http://localhost:8080/api/messages \
  -H 'Content-Type: application/json' \
  -d '{"sender":"alice","content":"agentul e cazut","target":"workers"}'
```

**Ce trebuie să vezi** — `503`, nu `202`:

```json
{ "status": 503, "error": "Service Unavailable", "path": "/api/messages" }
```

Fără confirmările de publicare, apelul ar fi răspuns `202` și mesajul s-ar fi
pierdut tăcut. Emițătorul e informat că mesajul **nu** a fost preluat.

```bash
docker compose start rabbitmq
```

Primul apel de după repornire mai poate da `503` cât timp conexiunea se reface;
reîncearcă după câteva secunde.

> **Atenție la demonstrație:** registrul receptorilor trăiește în memoria
> agentului. Dacă repornești containerul `broker` (nu doar `rabbitmq`), lista se
> golește și receptorii nu se re-înregistrează singuri — trebuie repornite și
> ele: `docker compose restart receiver-1 receiver-2 receiver-3`.

---

## 4. Unde te mai poți uita

Ce a stocat **agentul** însuși (colecțiile concurente din memorie):

```bash
curl http://localhost:8080/api/messages            # toate canalele
curl http://localhost:8080/api/messages/workers    # un singur grup
curl http://localhost:8080/api/messages/channels   # dimensiune / capacitate / evacuate
curl http://localhost:8080/api/messages/invalid    # respinse de validare
curl http://localhost:8080/api/receivers           # cu contoare per receptor
```

`/channels` arată stocarea transient la lucru:

```json
[ { "channel": "messager.queue.audit",   "buffered": 26, "capacity": 100, "received": 26, "evicted": 0 },
  { "channel": "messager.queue.workers", "buffered": 24, "capacity": 100, "received": 24, "evicted": 0 },
  { "channel": "messager.queue.reports", "buffered":  2, "capacity": 100, "received":  2, "evicted": 0 },
  { "channel": "messager.queue.dlq",     "buffered":  2, "capacity": 100, "received":  2, "evicted": 0 } ]
```

Coada de audit e legată cu `messager.#`, deci primește o copie a fiecărui mesaj
publicat, indiferent cui era adresat — e canalul cu cele mai multe mesaje.
Difuzările intră în ea o singură dată, chiar dacă ajung în fiecare grup.

`/api/receivers` arată câte mesaje a primit fiecare și câte dintre ele erau
adresate lui pe nume:

```bash
curl -s http://localhost:8080/api/receivers | grep -E '"name"|"delivered"|"addressedDirectly"'
```

Cozile și legăturile, în UI-ul RabbitMQ: <http://localhost:15672> (guest/guest) →
*Queues*.

---

## 5. Aceleași comenzi în PowerShell

`curl` din PowerShell 5.1 strică ghilimelele din JSON (primești `400` fără motiv
clar). Folosește `Invoke-RestMethod`:

```powershell
# ce poate fi adresat
Invoke-RestMethod http://localhost:9200/targets

# trimitere: grup, receptor anume, toți
Invoke-RestMethod -Method Post -Uri 'http://localhost:9200/send/group/workers?content=pentru grup'
Invoke-RestMethod -Method Post -Uri 'http://localhost:9200/send/to/r1?content=doar pentru r1'
Invoke-RestMethod -Method Post -Uri 'http://localhost:9200/send?target=all&content=pentru toti'

# direct la agent
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/messages `
  -ContentType 'application/json' `
  -Body '{"sender":"alice","content":"doar pentru r1","target":"@r1"}'

# verificare primire
Invoke-RestMethod http://localhost:9101/received
Invoke-RestMethod http://localhost:8080/api/receivers |
  Format-Table name, group, delivered, addressedDirectly, failed

# comutatoarele receptorilor
Invoke-RestMethod -Method Post -Uri http://localhost:9101/fail
Invoke-RestMethod -Method Post -Uri http://localhost:9101/heal
```

Comenzile `docker compose` sunt identice în ambele shell-uri.

---

## 6. Oprire

```bash
docker compose down          # oprește tot, păstrează datele
docker compose down -v       # șterge și volumul RabbitMQ
```

---

## 7. Ce scenariu acoperă ce obiectiv

| Obiectiv din lucrare | Scenariul |
|---|---|
| 1.b — număr variabil de canale | 8 |
| 1.c — unul-la-unu, pe grup | 1, 3 |
| 1.c — unul-la-unu, pe receptor anume | 2, 3 |
| 1.c — unul-la-mulți, în interiorul unui grup | 3b |
| 1.c — unul-la-mulți, între grupuri | 4 |
| 1.d — mesaje invalide, destinatari inexistenți | 5 |
| 1.d — politici la eșec de livrare | 6, 7 |
| 1.d — căderea agentului | 9 |
| 2 — comunicare emițător–agent–receptor peste IP | 1, 2, 4 (procese separate) |
| 2.b — tratare concurentă | 3 |
| 3.a — stocare în colecții concurente | secțiunea 4, `/api/messages/channels` |
| 4 — rutare | 1, 2, 4, 8 |

Verificarea automată a acelorași reguli: `./mvnw test` (17 teste), sau colecția
Postman: `npx newman run postman/messager.postman_collection.json` (40 de cereri,
67 de aserțiuni).
