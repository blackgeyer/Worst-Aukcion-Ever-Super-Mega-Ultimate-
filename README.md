# 🤡 WorstAukcionSuperMegaUltimate

> **⚠️ WARNING: DO NOT RUN THIS ON A PUBLIC SERVER!**  
> This plugin is an intentional parody, a pure shitpost, and a tribute to the chaotic era of "vibe coding". If you drop this onto a live server with real players, your TPS will crash, your database will weep, and your main thread will freeze into oblivion.

---

## 📜 The Vibe Coder Confession

We’ve all seen them: plugins written purely on "vibes" — zero architectural planning, zero async operations, and pure vibes-driven logic.

This plugin was crafted to embody that exact spirit. It's an exploration of what happens when you perform synchronous SQLite database queries directly inside GUI click events on the main server thread, wrap everything in bilingual lore, and swallow exceptions with polite apologies.

If you know my actual production work (like *A.P.V.E.*), you know **this is 100% a joke**. No packet-level optimizations were used here — only raw, unadulterated main-thread blocking.

---

## 💡 Revolutionary "Features" & Philosophical Choice

- **The Absolute Anti-Capitalist Market (Zero Purchasing):** You literally **cannot buy any items**. Clicking to purchase does nothing, fails silently, or locks up the transaction. This is not a bug — it is the *meaning of life*. It teaches players that material possessions are fleeting, greed is an illusion, and the auction house exists purely as a digital museum for items you can look at but never touch.
- **Ultron AI Incubation Engine:** Executes heavy, unindexed synchronous SQLite queries directly on the main Bukkit thread during every single inventory click. 
  - *Note on thermals:* If your processor suddenly starts creaking at 90°C or your hosting provider sends you a bill for $9,000/day, do not panic. Your server has simply rethought humanity and started creating a new Ultron directly on your CPU. 
  - *Disaster Prevention:* To prevent a new global disaster, open a urgent support ticket asking your hosting provider to stop scrolling political news on TikTok and split your server node in two with an axe. If you self-host on a local rig, hand that ticket (and the axe) to yourself.
- **Feature-Rich Exploits:** Item duplication risks, race conditions, and broken inventory sync are rebranded as *emergent gameplay*. Shift-clicking items into raw slots is a test of your players' moral integrity.
- **Polite Error Handling:** Who needs stack traces or debug logs when your console can politely apologize with `"Извините, но something went wrong..."`?
- **Bilingual Bicultural Lore:** Premium global auction experience featuring mixed lore like `Price / Цена` and `Expires in / Истёк через`.
- **Zero Heavy Dependencies:** Who needs connection pools, ORMs, or HikariCP when you can block the tick thread with raw, unfiltered JDBC?

---

## 🛠️ Commands

- `/ah` — Open the main auction museum (look, but don't buy).
- `/ah sell <price>` — Donate your item to the eternal database void.
- `/ah search <query>` — Search lots using raw material strings while cooking your CPU core.
- `/ah player <nickname>` — View listings that nobody can buy.
- `/ah my` — View your trapped inventory.
- `/ah expired` — Claim your expired items (if the main thread wakes up).

---

## 🚀 Installation

1. **Don't.**
2. Seriously, do not put this on a public server.
3. If you really want to test it locally on `localhost`:
   - Build the project with `./gradlew build`.
   - Place the compiled `.jar` into your `/plugins` folder alongside **Vault** and an economy provider (like *Ekonomia* or *EssentialsX*).

---

## ⚖️ License

Released under **AGPLv3**. We believe in actual Free Software, not corporate freeloading. If you're building a service on top of this, there are no cloud loopholes here — open your source.

*Made with 💖, chaotic energy, and zero async tasks.*
