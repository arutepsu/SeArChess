<p align="center">
  <img src="frontend/web-ui/public/assets/logo/logo512.cut.png" alt="Searchess logo" width="260" />
</p>

<h1 align="center"> Searchess</h1>

<p align="center">
  A chess platform built as a playground for clean software architecture.
</p>

---

## 📖 About

Searchess is a chess system that's much more than "just chess." It's a
hands-on exploration of how to build a real, evolving piece of software with
a serious architecture underneath: a Scala-based backend, a modern web
frontend, and a growing set of services around gameplay, bots, tournaments,
and analytics.

## 🎓 University Project

Searchess is developed as part of a software architecture course at
**HTWG Konstanz — University of Applied Sciences**. The chess theme gives us
a problem domain that's easy to reason about, so we can focus on what the
course is really about: architecture, design decisions, and trade-offs.

## 🏛️ Architecture & Principles

The codebase is guided by a few core ideas rather than one rigid framework:

- **Clean Architecture** — clear boundaries between domain, application, and adapters
- **Domain-Driven Design** — the chess domain stays expressive and framework-free
- **Functional programming** — immutability, pure transformations, explicit data flow
- **Modular service boundaries** — services own their own data and responsibilities
- **Event-driven / cloud-native thinking** — designed to grow into a distributed system
- **High test coverage** — behavior-focused tests as a safety net for refactors

## 🤖 Self-Trained AI

Searchess includes its own AI opponent: a self-trained Python service learned
from real **Lichess** games, living in its own repo —
[`searchess-ai-service`](https://github.com/arutepsu/searchess-ai-service).
It plugs into the Scala backend as an independent service, keeping the
"smart" part of the AI decoupled from the core game engine.

## 🎨 Visual Style

The Web UI ditches plain chess glyphs for a **pixel-art, Japanese-inspired**
look — pieces are animated sprite warriors instead of standard chess icons,
each with their own idle/move/attack animations.

## 🛠️ Tech Stack

**Backend**

![Scala](https://img.shields.io/badge/Scala-DC322F?style=for-the-badge&logo=scala&logoColor=white)
![sbt](https://img.shields.io/badge/sbt-1B5E20?style=for-the-badge&logo=scala&logoColor=white)
![http4s](https://img.shields.io/badge/http4s-FF6B6B?style=for-the-badge)
![Python](https://img.shields.io/badge/Python-3776AB?style=for-the-badge&logo=python&logoColor=white)

**Data & Infra**

![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?style=for-the-badge&logo=postgresql&logoColor=white)
![MongoDB](https://img.shields.io/badge/MongoDB-47A248?style=for-the-badge&logo=mongodb&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-DC382D?style=for-the-badge&logo=redis&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white)
![Envoy](https://img.shields.io/badge/Envoy-AC6199?style=for-the-badge&logo=envoyproxy&logoColor=white)

**Frontend**

![React](https://img.shields.io/badge/React-61DAFB?style=for-the-badge&logo=react&logoColor=black)
![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?style=for-the-badge&logo=typescript&logoColor=white)
![Vite](https://img.shields.io/badge/Vite-646CFF?style=for-the-badge&logo=vite&logoColor=white)

## 🚧 Current Focus

Right now the project is centered on:

- A clean, service-oriented backend with well-defined contracts between services
- A polished, pixel-art Web UI for playing, watching bots, and exploring analytics
- A Lichess-trained AI service playable as an opponent
- A persistence layer that can move between PostgreSQL and MongoDB through real
  application ports, not ad-hoc scripts
- Bot tournaments and analytics pipelines built on top of the same game core

## 🌱 Still Evolving

Searchess is a living university project — modules get refactored, services
get split out, and the architecture keeps maturing as the course progresses.
Expect things to change, and expect them to keep getting cleaner.

For operational use, either run `DryRun`, then `Execute`, then `ValidateOnly`,
or run `DryRun` followed by `Execute --validate-after-execute`. The inline
validation pass only runs after a successful execute report; failed execution
reports are returned without running validation.

### Future Extension: Admin API / Microservice

The current design is intentionally suitable for later exposure as an admin API
or dedicated migration service. The migration orchestration already lives behind
application ports; the CLI is only a thin operational shell around it.
