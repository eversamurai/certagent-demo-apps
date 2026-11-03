# Running the demo by hand, step by step

Every command, where to type it, and what you should see. Keep this open on a second screen.

**What you need open:**

| Name used below | What it is |
|---|---|
| **Terminal A** | A Git Bash window in `D:\dev\demo_apps`. Every demo command is typed here. |
| **Terminal B** | A Git Bash window in `D:\dev\cert-verif-app`. CertAgent runs here. You only start and stop it. |
| **Timeline** | A browser window at http://localhost:8088 (CertAgent's page). |
| **Probe** | A browser window at http://localhost:8090 (the customers' view, plus the live reasoning panel). |

---

## Part 0: Stop anything already running

Do this whenever you're unsure what's running, and always before a fresh start.

### 0.1 Stop CertAgent

- **If you started it in Terminal B:** click into Terminal B and press **Ctrl+C**. If Git Bash asks
  `Terminate batch job (Y/N)?`, type **Y** and press Enter. You're back at the `$` prompt.
- **If it was started somewhere else**, or you're not sure, type this in Terminal A:

  ```bash
  netstat -ano | grep ':8088 .*LISTENING'
  ```

  - **No output:** CertAgent isn't running. You're done.
  - **A line ending in a number**, e.g. `... LISTENING 40980`: that number is the process ID. Stop it with:

    ```bash
    taskkill //F //PID 40980
    ```

    (use your number). You should see `SUCCESS: The process with PID 40980 has been terminated.`

- **Check:** reload the Timeline browser. It should fail to load ("This site can't be reached").

### 0.2 Stop the lab and demo containers (only when you're finished for the day)

You don't need this between takes; `reset.sh` restarts everything. To shut down completely, in Terminal A:

```bash
docker compose -f compose.yml down
```

```bash
docker compose -f ../cert-verif-app/lab/docker-compose.yml down
```

**Check:** `docker ps` lists no `certagent-...` containers.

---

## Part 1: Start everything

### 1.1 Docker Desktop

1. Start **Docker Desktop** from the Start menu.
2. Wait until the bottom-left of its window says **Engine running** (about 1–2 minutes after a reboot).
3. **Check** in Terminal A:

   ```bash
   docker info --format '{{.ServerVersion}}'
   ```

   It prints a version such as `29.9.2`. If it says `command not found`, close Git Bash and open a new window.
   If it says `failed to connect`, Docker isn't ready yet: wait and try again.

### 1.2 Open the two terminals

- **Terminal A:** open Git Bash and type

  ```bash
  cd /d/dev/demo_apps
  ```

- **Terminal B:** open a second Git Bash and type

  ```bash
  cd /d/dev/cert-verif-app
  ```

  ```bash
  git branch --show-current
  ```

  It should print `muse-spark`.

### 1.3 Reset the lab (Terminal A)

CertAgent must be **stopped** first (Part 0.1).

```bash
demo/reset.sh
```

- **Takes** 30–60 seconds. Purple `[demo]` and blue `[lab]` lines scroll past.
- **It must end with:**

  ```
  probe: demo.lab is OK
  probe: shop.prod is WARN
  probe: accounts.lab is OK
  ...
    Ready.  Probe:    http://localhost:8090
  ```

- **If it stops with** `CertAgent is running on :8088`: go back to Part 0.1.

### 1.4 Open the Probe browser

1. Open http://localhost:8090. Make the window **half the screen** (960 wide).
2. **You should see three tiles:**

   | Tile | State | Details |
   |---|---|---|
   | demo.lab | **OK** (green) | issuer `CertAgent Lab CA Intermediate CA` |
   | shop.prod | **WARN** (amber) | "Works, but risky: expires in 3h 59m" |
   | accounts.lab | **OK** (green) | |

3. Under the tiles, the **Model reasoning · live** panel says `idle — waiting for the next decision`. If it says
   `relay offline`, run `docker compose -f compose.yml up -d` in Terminal A.

### 1.5 Start CertAgent (Terminal B)

CertAgent needs Java 21 or newer. Point Maven at JDK 23 first; the `java` on your PATH is Java 17, which fails
with `UnsupportedClassVersionError … class file version 65.0`.

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-23.0.1"
```

To set it for every new Git Bash window, run once:
`echo 'export JAVA_HOME="/c/Program Files/Java/jdk-23.0.1"' >> ~/.bashrc`

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--certagent.schedule.run-on-startup=false
```

- The log scrolls for 10–20 seconds. It's ready when you see a line containing
  **`Started CertAgentApplication`**. A line just above it says `Tomcat started on port 8088`.
- **Leave this window alone** from now on. It is CertAgent's log; don't type in it.
- **Check** in Terminal A:

  ```bash
  curl localhost:8088/api/status
  ```

  It prints `{"running":false,"hosting":"Meta Model API","lastRunId":null,"model":"muse-spark-1.3"}`.

### 1.6 Open the Timeline browser

1. Open http://localhost:8088 and make the window the **other half** of the screen.
2. **You should see:**
   - At the top: **CertAgent**, a chip `muse-spark-1.3 · Meta Model API`, status **idle**, and the button
     **Run cycle now**.
   - Below that, no runs yet.

**You're ready to record.**

---

## Part 2: Case 1, "untrusted certificate → Fixed" (case 2 happens in the same run)

### 2.1 Break demo.lab (Terminal A)

```bash
../cert-verif-app/lab/break.sh untrusted
```

- **Terminal shows** `demo.lab is now 'untrusted'`.
- **Check the Probe** within about 2 seconds. The **demo.lab** tile:
  - turns **red FAIL**;
  - reads **"certificate not trusted — unknown issuer"**, with `PKIX path building failed…` underneath;
  - shows the issuer **Rogue Corp Root** in red;
  - starts adding red squares to its strip.
- **Say:** "Real customers can't reach the shop right now."

### 2.2 Start the agent

Click **Run cycle now** on the Timeline. The status changes to running, and a new run appears.

### 2.3 What to watch, in order (about 40 seconds in total)

1. **Probe panel:** `— demo.lab —`, then the model's reasoning types out, e.g. *"Checking demo.lab live to confirm the
   untrusted issuer…"*, then **`→ calls verify_handshake(demo.lab) AUTO · lab`**.
2. More reasoning: *"…untrusted issuer CN=Rogue Corp Root… renewal is the least invasive fix"*, then
   **`→ calls renew_certificate(demo.lab) AUTO · lab`**.
3. **Timeline:** the **demo.lab** card's steps light up: **Observed → Diagnosed** ("untrusted issuer") **→
   Decided** (the reasoning as a large headline) **→ Policy** (`AUTO · lab`) **→ Applied → Verified**.
4. **Timeline card:**
   - **Before** panel in red: Rogue Corp Root.
   - **After** panel in green: CertAgent Lab CA, RSA 2048.
   - Outcome pill: **Fixed**.
5. **Probe:** demo.lab turns **green OK**. Compare its **Fingerprint** (e.g. `bc7dae086380`) with the fingerprint in
   the card's **After** panel: they're the same.
6. **Case 2:**
   - **Panel:** `— shop.prod —`, reasoning about "expires in ~3h… inside the 6h renew window", then
     **`→ calls renew_certificate(shop.prod) APPROVAL NEEDED · prod`**.
   - **Timeline:** a **shop.prod** card whose Policy step shows **APPROVAL NEEDED · prod**, with outcome
     **Needs approval**.
   - **Probe:** shop.prod stays amber; nothing was changed.
   - **Say:** "In production, the agent proposes the fix and stops for a human."
7. accounts.lab is **Healthy**. The model isn't even asked about it.

**The run is over** when the panel says `idle — …` and the Timeline status is back to **idle**. A summary of the run
appears pinned at the top of the Timeline.

---

## Part 3: Case 3, "the fix fails → automatic rollback → then fixed"

Don't reset. Case 1's cards stay on the Timeline underneath.

### 3.1 Break demo.lab twice (Terminal A)

```bash
../cert-verif-app/lab/break.sh weak
```

- **Probe:** demo.lab turns **amber WARN**: "Works, but risky: weak key: RSA 1024 bits". The Key field shows
  **RSA 1024** in amber.

```bash
../cert-verif-app/lab/break.sh reload
```

- **Terminal shows** `nginx config is now invalid: reloads will fail, the running workers keep serving`.
- **Probe:** no change. The site still works; only future reloads are broken.

### 3.2 Start the agent

Click **Run cycle now**.

### 3.3 What to watch

1. **Panel:** reasoning about the weak 1024-bit key, then `→ calls renew_certificate(demo.lab) AUTO · lab`.
2. **Timeline:** a new demo.lab card:
   - **Applied** (a new cert was installed);
   - then a **ROLLBACK** row: the new fingerprint struck through, ↩, the backup fingerprint restored;
   - a reason line containing **`[emerg] invalid parameter "max_fails=three"`**;
   - outcome **Rolled back**, and an **After rollback** panel saying it's the same certificate as before the change.
3. **Probe:** demo.lab stays **amber the whole time**. Check the strip: **no red squares** were added.
4. **Say:** "The new certificate couldn't be loaded safely, so the agent put the old one back. No customer saw an
   outage."

### 3.4 An engineer fixes the config (Terminal A)

```bash
rm ../cert-verif-app/lab/conf.d/zz-legacy.conf
```

No output means it worked.

### 3.5 Start the agent again

Click **Run cycle now**.

### 3.6 What to watch

1. **Timeline:** a new demo.lab card → **Applied → Verified** → **Fixed**. **Before** shows RSA 1024; **After**
   shows RSA 2048.
2. **Probe:** demo.lab turns **green OK** with **RSA 2048**. The strip changes from amber to green with no red in
   between.
3. **Say:** "Once a human fixed the root cause, the agent finished the job."

---

## Part 4 (optional): Case 4, "wrong hostname → Fixed"

### 4.1 Break accounts.lab (Terminal A)

```bash
../cert-verif-app/lab/break.sh hostname
```

**Probe:** accounts.lab turns **red FAIL**: "certificate is for a different hostname". The issuer is still the
trusted Lab CA; only the name is wrong.

### 4.2 Start the agent

Click **Run cycle now**.

### 4.3 What to watch

1. **Panel:** reasoning about the cert being for `legacy-accounts.lab`, then
   `→ calls renew_certificate(accounts.lab) AUTO · lab`.
2. **Timeline:** the accounts.lab card's **Diagnosed** step shows
   `hostname accounts.lab not in certificate SANs [legacy-accounts.lab]`, and the outcome is **Fixed**.
3. **Probe:** accounts.lab turns **green OK**, and its fingerprint matches the card's **After** panel.

---

## Part 5: Finish or start over

- **Record again:** stop CertAgent (Part 0.1, Ctrl+C in Terminal B), run `demo/reset.sh` (Part 1.3), start
  CertAgent (Part 1.5), and reload both browsers.
- **Done for the day:** stop CertAgent (Part 0.1), then the containers (Part 0.2), then quit Docker Desktop.

## Checking without the browser (optional)

In Terminal A:

| Check | Command | Expected |
|---|---|---|
| One site's state | `curl localhost:8090/api/state/demo.lab` | `OK`, `WARN` or `FAIL` |
| Agent busy? | `curl localhost:8088/api/status` | `"running":true` while it works, `false` when done |

## If something goes wrong

| You see | Do |
|---|---|
| `reset.sh`: "CertAgent is running on :8088" | Part 0.1, then run `reset.sh` again |
| Timeline won't load | CertAgent isn't running: Part 1.5 |
| **Run cycle now** is grey | A run is still going. Wait until the status says idle |
| A probe tile shows `HTTP 502` | `docker compose -f compose.yml up -d` in Terminal A |
| Panel: "relay offline" | `docker compose -f compose.yml up -d` in Terminal A |
| Panel stays idle during a run | CertAgent must be running; check `docker compose -f compose.yml logs reasoning-relay` |
| `docker`: "failed to connect" | Docker Desktop isn't running (Part 1.1), then Part 1.3 |
| A card ends **Unresolved** | Part 5 "Record again", then repeat that case |
| `break.sh reload`: "expected nginx -t to fail" | The broken snippet is already there; carry on with 3.2 |
