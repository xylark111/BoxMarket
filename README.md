# BoxMarket

Marketplace barter item-for-item untuk server BoxPvP. Target: Paper 1.20.1 (juga 1.20.x), Java 17 s/d 21.

## Build

Syarat: JDK 17+ dan Maven.

    mvn clean package

Hasil: `target/BoxMarket-1.0.0.jar` -> taruh di folder `plugins/` lalu restart server.

Tanpa install apa pun: upload folder ini ke repository GitHub, buka tab **Actions** -> **Build BoxMarket**
-> unduh artifact `BoxMarket-jar`.

## Cara pakai

| Perintah | Fungsi |
|---|---|
| `/bm` | Buka marketplace |
| `/bm sell` | Menu jual: taruh item, Save, pilih item pembayaran, atur jumlah, Save |
| `/bm my` | Listing milikmu (klik untuk ambil kembali) + tombol Claim hasil penjualan |
| `/bm limit` | Lihat jumlah listing aktif dan limit kamu (beserta asal limitnya) |
| `/bm reload` | Reload config (admin) |
| `/bm cancel <id>` | Batalkan listing (admin, ID tampil di lore listing) |

Pembeli membayar dengan item custom; item pembayaran itu disimpan untuk seller dan diambil lewat
tombol **Claim** di `/bm my` (aman walau seller offline).

## Permission

`boxmarket.use`, `boxmarket.sell`, `boxmarket.sell.limit.<N>` (tertinggi yang berlaku), `boxmarket.admin`.

## Config

- `custom-item-key`: key PDC item custom BoxPvP.
- `payment-items`: daftar item pembayaran. `custom-id` kosong = item polos tanpa PDC custom.
- `market.listing-expire-hours`: listing lewat batas hilang dari market, item diambil lewat My Listings.

## Catalog item harga (admin)

Admin/OP atau pemegang permission `boxmarket.catalog` mengatur item apa saja yang boleh jadi harga barter
langsung dari game, tanpa edit config:

| Perintah | Fungsi |
|---|---|
| `/bm catalog` | Buka GUI catalog. Klik item di **inventory kamu** (bawah) untuk menambah. Klik item di catalog untuk memilih, lalu tombol merah untuk menghapus. |
| `/bm catalog add` | Tambah item yang sedang dipegang |
| `/bm catalog remove <id>` | Hapus item dari catalog |
| `/bm catalog check` | Cek item di tangan: cocok dengan catalog atau tidak, dan apa bedanya |

- Item disimpan lengkap (nama, lore, custom model data, PDC) di `catalog.yml`, jadi item custom tampil dan
  dicocokkan persis. Item ber-PDC `custom-item-key` dicocokkan lewat PDC; item lain lewat kecocokan penuh.
- Item tidak bisa dihapus selama masih ada listing aktif yang memakainya sebagai harga.
- Item bawaan dari `payment-items` di config.yml juga bisa dihapus lewat GUI (disembunyikan lewat catalog.yml).
- Player biasa tidak bisa membuka atau mengubah catalog.

## Sisa waktu listing

Lore listing menampilkan "Sisa waktu" (hijau > 1 hari, kuning > 1 jam, merah di bawahnya) dan diperbarui otomatis
tiap 20 detik selama menu terbuka. Atur batasnya di `market.listing-expire-hours` (0 = tanpa batas).

## Limit listing per rank

Atur di `listing-limits` (config.yml), mirip `sethome-multiple` Essentials:

    listing-limits:
      default: 1
      vip: 3
      mvp: 5

- Nama rank cocok dengan **group LuckPerms** (otomatis), atau permission `boxmarket.limit.<nama>`.
- Player memakai limit **tertinggi** yang dia punya. `-1` = tanpa batas.
- Permission angka tetap bisa: `boxmarket.sell.limit.<angka>` (1-100). `boxmarket.limit.unlimited` = tanpa batas.
- Matikan pencocokan group LuckPerms dengan `listing-limits-use-groups: false`.
- Cek hasilnya dengan `/bm limit`.

## Suara

Klik tombol, ganti halaman, +/- jumlah, konfirmasi, beli, jual, claim, error, dan notifikasi barang terjual
punya suara sendiri. Semua bisa diubah atau dimatikan di bagian `sounds` pada config.yml
(format: `nama.suara.minecraft, volume, pitch`). `enabled: false` mematikan semuanya.
