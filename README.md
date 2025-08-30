# InfinitePlayers — Break the Slot Limit (Safely) 🧠⚡

[![Build](https://img.shields.io/badge/build-Maven%2FGradle-23a7f2)](#-build--install)
[![MC](https://img.shields.io/badge/Spigot%2FPaper-1.16%2B-green)](#-compatibility)
[![Platform](https://img.shields.io/badge/platform-Server%20side-orange)](#-features)

A lightweight, **production-ready** Spigot/Paper plugin that lets **everyone join even when “server is full”** — while protecting performance with **soft caps, join rate limits, TPS-aware gating, and a priority queue with tiers**. Includes **live editing** via `/infiniteplayers set ...` and a slick **status** readout.

> 💡 Works great on Paper (uses the official TPS API when available). Falls back gracefully on Spigot/CraftBukkit.

---

## ✨ Features

- **Slots bypass** — overrides `KICK_FULL` so the server never blocks joins due to `max-players`.
- **Fake max in server list** — configurable display mode:
  - `dynamic`: show `online + extra-slots`
  - `fixed`: show a constant `/fixed-max`
- **Soft cap** — deny/queue joins after a threshold to protect performance.
- **Join rate limiter** — allow ≤ X joins / Y seconds (global and **per-tier**).
- **TPS gate** — deny/queue while TPS is below a configurable minimum (1m or 5m window).
- **FIFO Queue** — optional join queue with position display; persists across reconnect attempts.
- **Priority tiers** — multiple permission-based queues (VIP → Plus → Default), FIFO within tier.
- **Per-tier overrides** — custom **soft caps**, **rate limits**, and **messages** per tier.
- **Live config editing** — `/infiniteplayers set ...` updates `config.yml` instantly (no restart).
- **Admin tools** — clear/remove/list the queue, print tier sizes, rich `/status`.

---

## 📦 Install

1. Download/build the JAR (see [Build & Install](#-build--install)).
2. Drop it into your `plugins/` folder.
3. Start the server once to generate `config.yml`.
4. (Optional) Tweak settings, then `/infiniteplayers reload`.

---

## 🔧 Commands

| Command | What it does |
|---|---|
| `/infiniteplayers status` | Shows current mode, caps, TPS, queue size, tiers, and per-tier overrides. |
| `/infiniteplayers reload` | Reloads `config.yml` and applies all settings immediately. |
| `/infiniteplayers queue list` | Shows queue totals and per-tier sizes. |
| `/infiniteplayers queue clear` | Clears the entire queue. |
| `/infiniteplayers queue remove <player>` | Removes a specific player from the queue. |
| `/infiniteplayers tiers` | Shows tier order and queued counts. |
| `/infiniteplayers set mode <dynamic\|fixed>` | Switch server-list display mode. |
| `/infiniteplayers set extra <number>` | Set extra slots for dynamic mode. |
| `/infiniteplayers set fixed <number>` | Set the fixed max for server list. |
| `/infiniteplayers set softcap-enabled <true\|false>` | Toggle global soft cap. |
| `/infiniteplayers set softcap <number>` | Set global soft cap limit. |
| `/infiniteplayers set softcap-message <text>` | Set global soft cap message. |
| `/infiniteplayers set rate-enabled <true\|false>` | Toggle global join rate limit. |
| `/infiniteplayers set rate-joins <n>` | Global allowed joins in window. |
| `/infiniteplayers set rate-seconds <n>` | Global rate window seconds. |
| `/infiniteplayers set rate-message <text>` | Global rate-limit message. |
| `/infiniteplayers set tps-enabled <true\|false>` | Toggle TPS gate. |
| `/infiniteplayers set tps-min <1.0–20.0>` | Minimum TPS to allow joins. |
| `/infiniteplayers set tps-window <1m\|5m>` | TPS averaging window. |
| `/infiniteplayers set tps-message <text>` | TPS message. |
| `/infiniteplayers set queue-enabled <true\|false>` | Toggle queue system. |
| `/infiniteplayers set queue-max <n>` | Set max queue size (0 = unlimited). |
| `/infiniteplayers set queue-message <text>` | Set queue join message. |
| `/infiniteplayers set tiers <perm1,perm2,...>` | Set tier order (highest → lowest). |
| `/infiniteplayers set tier-softcap <tier\|default\|index> <number\|off>` | Per-tier soft cap. |
| `/infiniteplayers set tier-rate <tier\|default\|index> <joins> <seconds\|off> [message...]` | Per-tier join rate override. |

> **Text arguments** support `&` color codes. Placeholders: `{online}`, `{limit}`, `{joins}`, `{seconds}`, `{tier}`, `{tps}`, `{minTps}`, `{pos}`, `{size}`.

---

## 🔐 Permissions

| Permission | Default | Description |
|---|---|---|
| `infiniteplayers.reload` | `op` | Access to all admin commands. |
| `infiniteplayers.bypass` | `op` | Bypass soft caps, rate limits, TPS gate, and queue. |
| `infiniteplayers.tier.vip` | `false` | Example highest-priority tier. |
| `infiniteplayers.tier.plus` | `false` | Example mid-priority tier. |

---

## ⚙️ `plugin.yml`

```yaml
name: InfinitePlayers
main: com.example.infiniteplayers.InfinitePlayers
version: 1.8
api-version: '1.16'

commands:
  infiniteplayers:
    description: Manage InfinitePlayers
    usage: /<command> reload|status|tiers|queue [list|clear|remove <player>] | set <...>
    permission: infiniteplayers.reload
    permission-message: You don't have permission to do that.

permissions:
  infiniteplayers.reload:
    description: Manage InfinitePlayers
    default: op
  infiniteplayers.bypass:
    description: Bypass all gates and queue
    default: op

  # Example tier permissions — assign in your perms plugin:
  infiniteplayers.tier.vip:
    default: false
  infiniteplayers.tier.plus:
    default: false
