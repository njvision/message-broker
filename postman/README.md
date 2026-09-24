# Colecția Postman

| Fișier | Ce e |
|---|---|
| `messager.postman_collection.json` | colecția — 54 de cereri în 9 foldere, cu 83 de aserțiuni |
| `messager-local.postman_environment.json` | adresele serviciilor din `docker compose` |

## Import

În Postman: **Import** → alege ambele fișiere → selectează mediul
*messager — local (docker compose)* din colțul din dreapta sus.

Adresele sunt și variabile ale colecției, deci merge și fără mediu; mediul e util
doar dacă ai schimbat porturile în `.env`.

## Înainte de rulare

```bash
./mvnw -DskipTests package
docker compose up -d --build
```

Așteaptă până când `docker compose ps` arată toate serviciile `healthy`.

## Foldere

Urmează scenariile din [`../DEMO.md`](../DEMO.md) și se rulează **în ordine** —
cererile de verificare folosesc id-urile și contoarele salvate de cele de
trimitere.

| Folder | Ce demonstrează |
|---|---|
| `00 — Verificare stare` | agentul e viu; grupurile s-au format din înregistrări, nu din configurație |
| `01 — Felurile de a adresa un mesaj` | grup, toți din grup, receptor anume, difuzare; plus validarea |
| `02 — Verificarea primirii` | mesajul a ajuns unde trebuia și nicăieri altundeva |
| `03 — Adresare directă vs. adresare pe grup` | 4 mesaje în fiecare fel, cu repartiția numărată |
| `03b — Către toți membrii unui grup` | `every:workers` ajunge la ambii din grup și la niciunul din afara lui |
| `03c — Chiar către toți: every:all` | singura formă care ajunge la toți cei trei receptori |
| `04 — Ce se întâmplă când receptorul nu răspunde` | comutare pentru grup, dead letter pentru adresare directă |
| `05 — Topologia se schimbă în timpul funcționării` | un receptor nou creează grupul lui |
| `06 — Inspecția agentului` | stocarea transient, canalele de infrastructură |

Folderul **03** e demonstrația centrală: trimite 4 mesaje către `@r1` și 4 către
grupul `workers`, apoi verifică în cifre că primele au mers toate la `r1` iar
ultimele s-au împărțit. Rulare reală:

```
r1: 14 -> 18                            (cele 4 directe)
r2: 8 -> 8                              (niciunul)
din 4 mesaje de grup: r1 a luat 2, r2 a luat 2
```

Două lucruri de știut:

- **Folderul 04 se rulează întreg.** Prima cerere strică intenționat receptorul
  `r1`; ultima îl repară. Dacă te oprești la mijloc, `r1` rămâne căzut.
- **Folderul 05 are nevoie de containerul `receiver-4`**, care nu pornește
  implicit (fără el, cele 3 aserțiuni ale ultimelor cereri eșuează cu
  `ECONNREFUSED`):
  ```bash
  docker compose --profile extra up -d receiver-4
  ```
  La final, oprește-l cu `docker compose --profile extra stop receiver-4`.

## Rulare din linia de comandă

```bash
npx newman run postman/messager.postman_collection.json
```

Sau un singur folder:

```bash
npx newman run postman/messager.postman_collection.json \
  --folder "03 — Adresare directă vs. adresare pe grup"
```

Rularea completă durează ~35 de secunde. Cererile care verifică primirea au o
așteptare în scriptul pre-request, ca livrarea să apuce să se producă — de aceea
unele par lente. (Postman nu are funcție de pauză, și `await` nu e suportat în
sandbox, așa că e o așteptare activă.)

Dacă o verificare eșuează cu *„retrimite cererea — e pe drum"*, chiar asta
înseamnă: mesajul încă nu ajunsese.
