# PACTO — Especificação do MVP

> **Ticket:** PCT-001 · **Sprint:** 01 (23–29/11/2026)  
> **Data da decisão:** 23/11/2026 · **Status:** APROVADO / DONE  
> **Natureza:** protótipo educativo com créditos fictícios; sem dinheiro real.

## 1. Visão e objetivo

O **PACTO** é uma aplicação de desafios entre dois participantes. Cada pessoa possui uma carteira de créditos fictícios e pode criar ou aceitar desafios, reservar créditos em custódia e confirmar um vencedor. O resultado concordante liquida o desafio; a discordância mantém os créditos bloqueados.

**Objetivo do produto:** demonstrar um fluxo transacional reproduzível de ponta a ponta.  
**Objetivo de engenharia:** comprovar idempotência persistente, integridade de um ledger, atomicidade e segurança sob requisições concorrentes, sem depender de microserviços.

### 1.1 Stack e limites arquiteturais

- **Backend:** Java + Spring Boot, monólito modular com API REST.
- **Frontend:** React + Vite + TypeScript, consumindo a API REST.
- **Persistência:** PostgreSQL; migrações versionadas com Flyway; ambiente local com Docker Compose.
- **Testes:** testes de regras de negócio e de integração com PostgreSQL real, incluindo concorrência.
- **Identidade no MVP:** exatamente dois usuários fixos de demonstração, selecionáveis na interface. **Não há autenticação real:** a identificação simulada é válida apenas para uso local/demonstração e não representa proteção contra impersonação.

## 2. Escopo

### 2.1 Incluído no MVP

1. Inicialização com dois usuários de demonstração e **100 créditos inteiros para cada um**, com origem da carga registrada no ledger.
2. Consulta de saldo disponível, saldo reservado e histórico de movimentações.
3. Criação de desafio com criador, destinatário, título, descrição e contribuição inteira positiva de cada participante.
4. Aceitação com reserva **atômica dos créditos de ambos os participantes**, somente no momento do aceite.
5. Registro de uma confirmação de vencedor por participante.
6. Liquidação única quando as duas confirmações concordarem.
7. Marcação de disputa e manutenção da custódia quando houver discordância.
8. Cancelamento pelo criador **somente enquanto o desafio estiver OPEN**.
9. `Idempotency-Key` persistida no PostgreSQL para comandos que alteram estado.
10. Controle transacional e concorrência para impedir reservas e liquidações duplicadas.
11. Interface simples para realizar e demonstrar a jornada completa.
12. Instruções de execução, cenários e evidências de testes.

### 2.2 Fora do MVP

- Dinheiro real, apostas financeiras, conversão, depósitos, saques, integrações bancárias ou pagamentos.
- Cadastro, login, permissões reais, recuperação de senha e múltiplas contas arbitrárias.
- Resolução administrativa de disputas: no MVP, `DISPUTED` é um estado terminal e o valor segue bloqueado.
- Cancelamento de desafios aceitos, expiração automática, estorno manual e alteração de resultado.
- Microserviços, filas, Kafka, Redis, notificações, ranking, chat ou aplicativo móvel nativo.
- Implantação pública como sistema de produção.

## 3. Vocabulário

- **Créditos:** unidades inteiras fictícias, sem valor monetário.
- **Saldo disponível:** créditos que o participante pode comprometer em novos desafios.
- **Custódia / saldo reservado:** créditos já separados da disponibilidade e ainda não liquidados.
- **Ledger:** registro auditável e imutável de todas as movimentações de créditos, inclusive carga inicial e transferências para/de custódia.
- **Desafio:** acordo entre criador e destinatário que compromete a mesma quantidade de créditos de cada um.
- **Liquidação:** transferência final da custódia para o vencedor confirmado por ambos.
- **Requisição idempotente:** repetição de um comando com a mesma chave e payload sem novo efeito de negócio.

## 4. Regras de negócio

| ID | Regra |
|---|---|
| **RN-001** | Existem dois usuários de demonstração (A e B), cada um iniciado com 100 créditos; a carga inicial deve ter rastreabilidade no ledger. |
| **RN-002** | Somente um usuário pode criar desafio direcionado ao outro; não se pode desafiar a si mesmo. A contribuição por participante é um inteiro maior que zero. |
| **RN-003** | Criar um desafio apenas registra a proposta com estado `OPEN`: **não reserva créditos**. |
| **RN-004** | Apenas o destinatário pode aceitar um desafio `OPEN`. No aceite, o sistema verifica a disponibilidade de **ambos** e reserva a contribuição dos dois em uma única transação. Se qualquer validação falhar, nenhuma reserva ocorre. |
| **RN-005** | Depois do aceite, cada participante pode informar **uma vez**, de maneira definitiva, qual dos dois venceu. A primeira confirmação muda o estado para `AWAITING_RESULT`. |
| **RN-006** | Se ambas as confirmações apontarem para o mesmo vencedor, os créditos da custódia são creditados integralmente ao vencedor e o desafio vira `SETTLED`, uma única vez. |
| **RN-007** | Se as confirmações divergirem, o desafio vira `DISPUTED`; os créditos permanecem em custódia, sem liberação automática. |
| **RN-008** | Somente o criador pode cancelar um desafio ainda `OPEN`. O cancelamento muda o estado para `CANCELLED` sem movimentar créditos. |
| **RN-009** | Comandos de escrita exigem `Idempotency-Key`. A identidade simulada do usuário, a operação e a chave delimitam seu escopo. A mesma chave com mesmo payload reutiliza a resposta final armazenada; com payload diferente, gera conflito, sem novo efeito. |
| **RN-010** | Requisições concorrentes, inclusive com **chaves diferentes**, não podem aceitar, reservar, confirmar ou liquidar o mesmo evento mais de uma vez. |

## 5. Máquina de estados

```text
OPEN --[criador cancela]--> CANCELLED
  |
  +--[destinatário aceita e ambos reservam saldo]--> ACCEPTED
                                                      |
                                                      +--[1ª confirmação]--> AWAITING_RESULT
                                                                               |
                                                                               +--[2ª concorda]--> SETTLED
                                                                               |
                                                                               +--[2ª diverge]----> DISPUTED
```

- `OPEN`: aguardando aceite; nenhuma reserva efetuada.
- `ACCEPTED`: reservado para ambos; aguardando a primeira confirmação.
- `AWAITING_RESULT`: exatamente uma confirmação registrada; aguardando a segunda.
- `SETTLED`: estado terminal; custódia referente ao desafio zerada e vencedor creditado.
- `DISPUTED`: estado terminal neste MVP; custódia permanece bloqueada.
- `CANCELLED`: estado terminal; não houve reserva.

Operações fora dessas transições são rejeitadas sem efeitos financeiros. Os resultados só podem nomear um dos dois participantes do desafio; não se admite edição da confirmação.

## 6. Invariantes de integridade

| ID | Invariante verificável |
|---|---|
| **INV-001** | Nenhuma carteira de usuário pode apresentar saldo disponível negativo. |
| **INV-002** | A movimentação de créditos entre usuários e custódia não cria nem destrói créditos; cada operação contabilizada é balanceada. A emissão inicial de créditos é uma operação identificável e auditável. |
| **INV-003** | Toda mudança de crédito tem lançamento correspondente no ledger; lançamentos já confirmados não são alterados ou apagados. |
| **INV-004** | O aceite reserva exatamente uma contribuição de **cada** participante ou nenhuma delas. |
| **INV-005** | Um desafio só pode ser liquidado uma vez; não há pagamento parcial nem repetido. |
| **INV-006** | Falha em qualquer etapa de um comando transacional reverte os efeitos desse comando. |
| **INV-007** | Créditos associados a desafios `DISPUTED` permanecem em custódia e não ficam disponíveis. |
| **INV-008** | Cada participante registra no máximo uma confirmação por desafio; um usuário fora do desafio não pode confirmar. No MVP, a identidade é simulada, não autenticada. |
| **INV-009** | A mesma `Idempotency-Key` com o mesmo usuário/operação/payload não gera novo efeito; o mesmo identificador usado com payload diferente é rejeitado. |
| **INV-010** | Concorrência não viola unicidade das reservas, confirmações, transições ou lançamentos. |

## 7. Política de idempotência e concorrência

- Todos os comandos mutáveis (`criar`, `aceitar`, `cancelar`, `confirmar`) recebem uma `Idempotency-Key`.
- O backend grava, em PostgreSQL, a identidade simulada do solicitante, o tipo de operação, a chave, o hash do conteúdo normalizado e a resposta final associada ao processamento concluído.
- Repetir chave + contexto + payload **após conclusão** devolve o mesmo resultado registrado, sem repetir efeitos.
- Reutilizar chave + contexto com payload diferente retorna **HTTP 409**, sem movimentar créditos.
- Duas execuções ainda simultâneas da mesma chave devem ser serializadas ou receber indicação transitória de operação em processamento, **sem novo efeito**; o retry posterior deverá encontrar o resultado final.
- Restrições de unicidade e transações/bloqueios no PostgreSQL também devem proteger contra requisições simultâneas com chaves **diferentes**.
- Em caso de falha técnica antes do commit, não pode restar uma movimentação parcial nem uma resposta de sucesso fictícia. Política exata de respostas transitórias e erros será detalhada no contrato de API.

## 8. Cenários de aceite do MVP

| ID | Dado / Quando | Então |
|---|---|---|
| **CA-001** | Usuários A e B recebem carga inicial | Cada um possui 100 disponíveis; a origem dos créditos aparece no ledger. |
| **CA-002** | A cria desafio de 20 créditos contra B | O desafio fica `OPEN`; A e B permanecem com 100 disponíveis. |
| **CA-003** | B aceita desafio de 20 créditos | A e B ficam com 80 disponíveis cada; 40 ficam em custódia; desafio `ACCEPTED`. |
| **CA-004** | A e B confirmam A como vencedor | Desafio `SETTLED`; A fica com 120, B com 80, custódia do desafio em 0; lançamentos balanceados. |
| **CA-005** | A indica A e B indica B | Desafio `DISPUTED`; A e B ficam com 80 disponíveis; 40 permanecem bloqueados. |
| **CA-006** | B tenta aceitar quando qualquer participante não tem saldo | Aceite rejeitado; nenhuma carteira, reserva ou lançamento é alterado. |
| **CA-007** | A cancela desafio `OPEN` | Estado `CANCELLED`, nenhuma reserva criada; aceite posterior rejeitado. |
| **CA-008** | Participante tenta confirmar duas vezes ou terceiro tenta confirmar | Operação inválida rejeitada; resultado e créditos não são alterados. Um retry legítimo da mesma confirmação com mesma chave retorna o resultado original. |
| **CA-009** | Cliente repete o aceite com chave e payload idênticos após conclusão | Mesma resposta gravada; uma reserva por participante; nenhum lançamento duplicado. |
| **CA-010** | Cliente usa a mesma chave para payload diferente | HTTP 409; sem novo efeito. |
| **CA-011** | 10–20 chamadas concorrentes tentam aceitar o mesmo desafio | No máximo um aceite efetivo e um par de reservas, inclusive com chaves diferentes; saldos e ledger permanecem íntegros. |
| **CA-012** | Falha é provocada no meio de uma reserva ou liquidação | Nenhum efeito parcial fica persistido; o desafio e os saldos permanecem consistentes. |
| **CA-013** | Duas confirmações chegam simultaneamente | O desafio alcança um único estado final correto, sem liquidação duplicada. |

**Nota:** CA-004 e CA-005 são cenários independentes, executados a partir de bases/cenários reinicializados.

## 9. Registro das decisões (23/11/2026)

| Decisão | Escolha aprovada | Motivo / impacto |
|---|---|---|
| **DEC-001 — Valor** | Créditos inteiros positivos, limitados ao saldo disponível de **ambos** no aceite | Evita valores fracionários e preserva integridade. |
| **DEC-002 — Identidade** | Dois usuários fixos, selecionáveis pela interface | Prioriza regras transacionais; não é autenticação para produção. |
| **DEC-003 — Reserva** | Reservar ambas as contribuições somente no aceite | Uma transação atômica na mudança de `OPEN` para `ACCEPTED`. |
| **DEC-004 — Cancelamento** | Somente criador, apenas antes do aceite | Evita estorno após reserva nesta primeira versão. |
| **DEC-005 — Disputas** | Manter créditos em custódia até versão futura | Nenhuma decisão unilateral ou desbloqueio automático. |
| **DEC-006 — Idempotência** | Persistir chave, hash e resultado no PostgreSQL | Garante rastreabilidade e proteção contra retries após reinício. |

## 10. Critérios de conclusão do PCT-001

- [x] Objetivo, escopo e exclusões explicitados.
- [x] As seis decisões de produto aprovadas e registradas.
- [x] Regras de criação, reserva, confirmação, liquidação, disputa e cancelamento documentadas.
- [x] Estados e transições válidas definidos.
- [x] Invariantes de saldo, ledger, idempotência, rollback e concorrência registrados.
- [x] Cenários de sucesso, erro e chamadas concorrentes descritos com resultados esperados.

**Status final: PCT-001 — DONE.** Esta aprovação é de **escopo/documentação**; não implica implementação ou testes executados.

## 11. Próximo ticket — PCT-002 (não iniciado)

**Título:** Modelar entidades, relacionamentos e máquina de estados.  
**Prioridade:** P0 · **Estimativa do roadmap:** 45 minutos.

**Entregáveis previstos:**

1. Diagrama de domínio com `User`, `Wallet`, `Challenge`, `Confirmation`, `LedgerEntry` e `IdempotencyRecord`; avaliar representação de custódia e conta de origem de créditos.
2. Cardinalidades, identificadores, constraints e índices de unicidade essenciais.
3. Responsabilidades dos módulos `wallet`, `challenge` e `idempotency` do monólito.
4. Tabela de transições e operações de escrita com suas fronteiras transacionais.
5. Registro de decisões técnicas ainda não fechadas: representação contábil da custódia, estratégia de saldo materializado, versão/concorrência e contrato HTTP para operação em processamento.

**Critério de aceite do PCT-002:** modelo conceitual e transições revisados contra RN-001 a RN-010 e INV-001 a INV-010, prontos para orientar as migrations e os testes. **Nenhum código é necessário nesta etapa.**
