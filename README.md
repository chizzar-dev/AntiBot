<div align="center">

<img src="https://capsule-render.vercel.app/api?type=rect&color=0:0b1220,100:0e7490&height=110&section=header&text=AntiBot&fontSize=42&fontColor=22d3ee&fontAlignY=54&desc=Connection%20limits%20and%20a%20GUI%20check%20against%20bots&descSize=13&descColor=94a3b8&descAlignY=80" width="100%" alt="AntiBot" />

<p>
<img src="https://img.shields.io/github/v/release/chizzar-dev/AntiBot?style=flat&label=release&color=06b6d4&labelColor=0b1220" alt="release" />
<img src="https://img.shields.io/badge/Minecraft-1.8%20%E2%80%93%201.21.11-0891b2?style=flat&labelColor=0b1220" alt="Minecraft 1.8 - 1.21.11" />
<img src="https://img.shields.io/badge/Java-8%2B-155e75?style=flat&labelColor=0b1220&logo=openjdk&logoColor=22d3ee" alt="Java 8+" />
<a href="LICENSE"><img src="https://img.shields.io/github/license/chizzar-dev/AntiBot?style=flat&label=license&color=0e7490&labelColor=0b1220" alt="license" /></a>
</p>

</div>

AntiBot defends against join-flood attacks with connection rate limits, per-IP caps, an optional name filter and a lightweight GUI verification. Join checks run off the main thread.

*AntiBot, bot saldirilarina karsi baglanti hizi limiti, IP limiti, istege bagli isim filtresi ve hafif bir GUI dogrulamasiyla savunma yapar. Giris kontrolleri ana thread disinda calisir.*

## Features · Özellikler
- **Bağlantı hızı limiti** — kısa sürede çok fazla giriş olursa otomatik **kilit moduna** geçer
- **IP limitleri** — aynı IP'den eş zamanlı ve dakikalık giriş sınırı
- **İsim filtresi** — `Player1234` gibi bot kalıplarını regex ile reddeder
- **GUI doğrulama** — yeni oyuncu doğru eşyaya tıklayana kadar hareket edemez, konuşamaz, vuramaz
- Bir kez doğrulanan oyuncu kaydedilir, bir daha sorulmaz
- Saldırı bildirimi `antibot.notify` yetkisi olanlara gider
- Giriş kontrolleri **ana thread dışında** yapılır; saldırı anında bile sunucu donmaz
- Hiçbir bağımlılığı yok

## Layers · Katmanlar
| Katman | Ne yapar |
| :-- | :-- |
| 1. Bağlantı hızı | `interval-seconds` içinde `max-joins` aşılırsa kilit modu açılır |
| 2. IP limitleri | Aynı IP'den eş zamanlı ve dakikalık giriş sayısını sınırlar |
| 3. İsim filtresi | Regex kalıplarına uyan kullanıcı adlarını reddeder |
| 4. GUI doğrulama | Oyuncu doğru eşyaya tıklayana kadar donar |

> Kilit modundayken **daha önce doğrulanmış oyuncular girmeye devam eder** — saldırı sırasında
> sunucun düzenli oyuncularına kapanmaz.

## Installation · Kurulum
1. [Releases](https://github.com/chizzar-dev/AntiBot/releases/latest) sayfasından `AntiBot.jar` dosyasını indir.
2. Sunucunun `plugins/` klasörüne at.
3. Sunucuyu yeniden başlat.
4. `plugins/AntiBot/config.yml` dosyasından limitleri sunucunun büyüklüğüne göre ayarla.

## Commands · Komutlar
| Komut | Açıklama | Yetki |
|-------|----------|-------|
| `/antibot status` | Kilit modu ve sayaç durumunu gösterir | `antibot.admin` |
| `/antibot lock` | Kilit modunu elle açar | `antibot.admin` |
| `/antibot unlock` | Kilit modunu kapatır | `antibot.admin` |
| `/antibot reload` | Ayarları yeniden yükler | `antibot.admin` |

**Alias:** `/ab`

## Permissions · Yetkiler
| Yetki | Açıklama | Varsayılan |
|-------|----------|------------|
| `antibot.bypass` | Doğrulamadan muaf tutulur | op |
| `antibot.notify` | Saldırı bildirimlerini görür | op |
| `antibot.admin` | AntiBot yönetimi | op |

## Configuration · Ayarlar
| Anahtar | Açıklama |
|---------|----------|
| `join-rate.max-joins` / `interval-seconds` | Bu süre içinde bu sayıyı aşan giriş = saldırı |
| `join-rate.lockdown-seconds` | Kilit modu kaç saniye sürer |
| `per-ip.max-online` | Aynı IP'den eş zamanlı en fazla oyuncu |
| `per-ip.max-joins-per-minute` | Aynı IP'den dakikada en fazla giriş |
| `name-filter.blocked-patterns` | Reddedilecek isim kalıpları (regex) |
| `captcha.only-first-join` | Doğrulama yalnızca ilk girişte sorulsun |
| `captcha.only-during-attack` | Doğrulama yalnızca kilit modunda sorulsun |
| `captcha.attempts` / `timeout` | Hak sayısı ve süre sınırı |
| `whitelist-ips` | Tüm kontrollerden muaf IP'ler |

> ⚠️ Sunucun bir **proxy** (BungeeCord / Velocity) arkasındaysa tüm bağlantılar proxy'nin IP'sinden
> gelir. Bu durumda `per-ip` limitlerini kapat (`0`) ya da proxy IP'sini `whitelist-ips` listesine ekle.

## Building · Derleme
```bash
mvn clean package
```
Çıktı · Output: `target/AntiBot.jar`

Her push [GitHub Actions](https://github.com/chizzar-dev/AntiBot/actions/workflows/build.yml) ile derlenir; `v*` etiketli sürümler jar'la birlikte [Releases](https://github.com/chizzar-dev/AntiBot/releases) sayfasına eklenir.
<br><sub>Every push is built by GitHub Actions; tagged `v*` releases attach the jar.</sub>

## License · Lisans
[MIT](LICENSE) — istediğin gibi kullan, değiştir, dağıt · use, modify and distribute freely

<div align="center"><sub>chizzar-dev · Minecraft plugins for 1.8 – 1.21.11</sub></div>
