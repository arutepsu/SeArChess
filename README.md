<p align="center">
  <img src="frontend/web-ui/public/assets/logo/logo512.cut.png" alt="Searchess logo" width="260" />
</p>

<h1 align="center"> Searchess</h1>

<p align="center">
  A chess platform built as a playground for clean software architecture.
</p>

<p align="center">
  <a href="https://github.com/arutepsu/SeArChess/actions/workflows/ci.yml">
    <img src="https://github.com/arutepsu/SeArChess/actions/workflows/ci.yml/badge.svg" alt="CI" />
  </a>
  <a href="https://github.com/arutepsu/SeArChess/actions/workflows/build-images.yml">
    <img src="https://github.com/arutepsu/SeArChess/actions/workflows/build-images.yml/badge.svg" alt="Docker Build" />
  </a>
  <a href="https://coveralls.io/github/arutepsu/SeArChess?branch=main">
    <img src="https://coveralls.io/repos/github/arutepsu/SeArChess/badge.svg?branch=main" alt="Coverage Status" />
  </a>
  <a href="https://github.com/arutepsu/SeArChess/actions/workflows/codeql.yml">
    <img src="https://github.com/arutepsu/SeArChess/actions/workflows/codeql.yml/badge.svg" alt="CodeQL" />
  </a>
  <img src="https://img.shields.io/github/last-commit/arutepsu/SeArChess" alt="Last commit" />
  <a href="https://opensource.org/licenses/MIT">
    <img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License: MIT" />
  </a>
</p>

<p align="center">
  <img src="https://media4.giphy.com/media/v1.Y2lkPTc5MGI3NjExNXVhc2l4Y241NXV2bGl2bjg0b2R5dDZudG45N2ZrcGFhamN6cjI2YiZlcD12MV9pbnRlcm5hbF9naWZfYnlfaWQmY3Q9Zw/LjXbY46jQtONhnfUoe/giphy.gif" alt="Searchess gameplay demo" width="560" />
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

## 📚 Documentation

- [Architecture](docs/architecture.md) — overview of the system architecture, deployable services, data flows, analytics, persistence, and deployment.
- [Features Guide](docs/features.md) — visual overview of the Web UI, game experience, tournaments, analytics, profile features, and screenshots.

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

<p align="center"><strong>Backend</strong></p>

<p align="center">
  <img src="https://img.shields.io/badge/Scala-DC322F?style=for-the-badge&logo=scala&logoColor=white" alt="Scala" />
  <img src="https://img.shields.io/badge/sbt-1B5E20?style=for-the-badge&logo=scala&logoColor=white" alt="sbt" />
  <img src="https://img.shields.io/badge/http4s-FF6B6B?style=for-the-badge" alt="http4s" />
  <img src="https://img.shields.io/badge/Python-3776AB?style=for-the-badge&logo=python&logoColor=white" alt="Python" />
</p>

<p align="center"><strong>Data & Infra</strong></p>

<p align="center">
  <img src="https://img.shields.io/badge/PostgreSQL-4169E1?style=for-the-badge&logo=postgresql&logoColor=white" alt="PostgreSQL" />
  <img src="https://img.shields.io/badge/MongoDB-47A248?style=for-the-badge&logo=mongodb&logoColor=white" alt="MongoDB" />
  <img src="https://img.shields.io/badge/Redis-DC382D?style=for-the-badge&logo=redis&logoColor=white" alt="Redis" />
  <img src="https://img.shields.io/badge/Kafka-231F20?style=for-the-badge&logo=apachekafka&logoColor=white" alt="Kafka" />
  <img src="https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white" alt="Docker" />
  <img src="https://img.shields.io/badge/Kubernetes-326CE5?style=for-the-badge&logo=kubernetes&logoColor=white" alt="Kubernetes" />
  <img src="https://img.shields.io/badge/Envoy-AC6199?style=for-the-badge&logo=envoyproxy&logoColor=white" alt="Envoy" />
  <img src="https://img.shields.io/badge/Apache%20Spark-E25A1C?style=for-the-badge&logo=apachespark&logoColor=white" alt="Apache Spark" />
</p>

<p align="center"><strong>Frontend</strong></p>

<p align="center">
  <img src="https://img.shields.io/badge/React-61DAFB?style=for-the-badge&logo=react&logoColor=black" alt="React" />
  <img src="https://img.shields.io/badge/TypeScript-3178C6?style=for-the-badge&logo=typescript&logoColor=white" alt="TypeScript" />
  <img src="https://img.shields.io/badge/Vite-646CFF?style=for-the-badge&logo=vite&logoColor=white" alt="Vite" />
</p>

---

<p align="center">
  Released under the <a href="https://opensource.org/licenses/MIT">MIT License</a>.
</p>
