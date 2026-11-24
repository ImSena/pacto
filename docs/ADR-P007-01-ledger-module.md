# ADR-P007-01 — Módulo Ledger Independente

## Status

Aceito

## Data

2026-11-24

## Contexto

O Ledger (livro-razão) foi inicialmente projetado como um componente
interno do módulo Wallet. Entretanto, as operações contábeis possuem
responsabilidades, entidades e restrições de integridade próprias.

Manter o Ledger dentro do módulo Wallet gera um acoplamento entre
as regras contábeis e o gerenciamento das carteiras dos usuários.

## Decisão

- Criar um módulo independente: `dev.pacto.ledger`.
- Centralizar as operações contábeis no `LedgerService`.
- Restringir as alterações de saldo e a criação de lançamentos
  contábeis utilizando a visibilidade `package-private`.
- Referenciar carteiras e desafios por UUID, em vez de utilizar
  associações JPA diretas entre módulos.
- Manter as chaves estrangeiras no banco de dados para preservar
  a integridade referencial.
- Preservar a arquitetura de monólito modular e os limites
  transacionais compartilhados no PostgreSQL.

## Consequências

### Positivas

- Maior clareza nas responsabilidades dos módulos.
- Redução do acoplamento entre Wallet e Ledger.
- Maior encapsulamento das regras de domínio.
- Testes independentes das regras contábeis.
- Reutilização das operações contábeis.

### Negativas

- Ausência de navegação automática via JPA entre as contas
  do Ledger e as carteiras.
- Consultas entre módulos exigem identificadores ou queries
  explícitas.
- A visibilidade `package-private` não garante a imutabilidade
  dos registros no nível do banco de dados.

## Escopo

Esta decisão refina os limites entre os módulos definidos na
PCT-002, sem substituir as decisões estabelecidas nas
ADR-P002-01 até ADR-P002-05.

O controle geral de concorrência e a idempotência HTTP permanecem
em suas respectivas tarefas previstas no roadmap.
