# PACTO

**Plataforma de desafios com créditos fictícios, desenvolvida para estudar idempotência, concorrência e integridade transacional.**

## Sobre o projeto

O PACTO é uma aplicação full stack na qual dois participantes podem criar desafios, reservar créditos fictícios e confirmar resultados.

O principal objetivo é demonstrar conceitos de engenharia de software relacionados à confiabilidade de operações, incluindo:

- Idempotência de requisições HTTP.
- Transações ACID e rollback.
- Controle de concorrência com PostgreSQL.
- Ledger contábil com lançamentos balanceados.
- Integridade de dados sob falhas e requisições simultâneas.

O sistema utiliza uma arquitetura monolítica modular, sem microserviços.

## Tecnologias

**Backend**

- Java 21
- Spring Boot 4
- Maven
- Spring Data JPA
- PostgreSQL 17
- Flyway

**Frontend**

- React
- TypeScript
- Vite

**Infraestrutura**

- Docker Compose
- Git

## Fluxo de negócio previsto

1. Dois usuários de demonstração recebem 100 créditos fictícios cada.
2. Um participante cria um desafio com uma contribuição inteira positiva.
3. O destinatário aceita o desafio.
4. O sistema reserva atomicamente os créditos dos dois participantes.
5. Cada participante confirma quem venceu.
6. Se ambos concordarem, os créditos reservados são transferidos para o vencedor.
7. Se discordarem, os créditos permanecem bloqueados em custódia.

Exemplo: Bruno e Lucas começam com 100 créditos cada. Ambos reservam 20 para um desafio. Se Bruno for confirmado como vencedor pelos dois, ele terminará com 120 créditos disponíveis e Lucas com 80.

## Arquitetura

O backend é um monólito modular Spring Boot organizado por responsabilidades de domínio:

- `challenge`: ciclo de vida dos desafios, aceite, confirmações e disputas.
- `wallet`: carteiras, contas, custódia, saldos e ledger.
- `idempotency`: tratamento de chaves idempotentes e proteção contra efeitos duplicados.

O frontend React consome a API REST do backend.

A persistência utiliza PostgreSQL, com esquema gerenciado por migrations Flyway.

As movimentações contábeis serão registradas em um ledger imutável, com saldos materializados reconciliáveis. Operações críticas utilizarão transações e mecanismos de bloqueio para preservar a consistência.

## Como executar localmente

### Pré-requisitos

- JDK 21
- Node.js e npm compatíveis com o Vite
- Docker com Docker Compose

### 1. Configurar o banco de dados

Na raiz do projeto, crie o arquivo de configuração local:

```bash
cp .env.example .env.pacto
```

Defina uma senha local em `.env.pacto`. Esse arquivo não deve ser versionado.

Inicie o PostgreSQL:

```bash
docker compose --env-file .env.pacto up -d
```

### 2. Iniciar o backend

No terminal, a partir da raiz do repositório:

```bash
set -a
source .env.pacto
set +a

cd backend
./mvnw spring-boot:run
```

O backend estará disponível em `http://localhost:8080`.

Para verificar sua saúde:

```bash
curl http://localhost:8080/actuator/health
```

### 3. Iniciar o frontend

Em outro terminal:

```bash
cd frontend
npm install
npm run dev
```

Abra o endereço informado pelo Vite, normalmente `http://localhost:5173`.

## Testes e validação

Para executar os testes do backend, com o banco de desenvolvimento configurado:

```bash
cd backend
./mvnw clean verify
```

Para validar o build do frontend:

```bash
cd frontend
npm run build
```

## Limitações

Este é um projeto educacional e de portfólio.

Os créditos não possuem valor monetário. Não há depósitos, saques, pagamentos reais ou integração bancária.

O MVP utilizará usuários fixos de demonstração, sem autenticação real. Resultados contestados permanecerão com créditos em custódia, sem resolução administrativa nesta versão.

O sistema não deve ser utilizado como serviço financeiro de produção.

## Documentação técnica

- [Especificação do MVP](docs/mvp.md)
- [Modelo de domínio e ADRs](docs/domain-model.md)

## Autor

Bruno Moura — projeto pessoal de engenharia de software.
