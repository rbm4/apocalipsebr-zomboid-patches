# Atualização do servidor - Melhoria na visão/percepção dos zumbis

Pessoal, saiu mais uma otimização no servidor! Essa aqui é sobre como o servidor calcula o que
cada jogador consegue ver (isso afeta a percepção dos zumbis, sala "explorada", e a visibilidade
em geral no PvP e no PvE).

## O que mudou

Antes, o servidor calculava a visão dos jogadores **um por um, em fila**, numa única thread
dedicada. Com muita gente online ao mesmo tempo, isso virava um gargalo: quanto mais jogadores
conectados, mais cada um esperava a vez dele ser processada.

Agora esse cálculo roda **em paralelo**, aproveitando os núcleos de CPU disponíveis no servidor.
Ou seja, vários jogadores podem ter sua visão recalculada ao mesmo tempo, ao invés de esperar em
fila.

## O que vocês devem sentir na prática

- **Menos "atraso" na hora dos zumbis notarem o jogador** em servidores cheios (picos de
  horário), já que o cálculo de visão não fica represado.
- **Comportamento de jogo idêntico** - isso é uma otimização de performance interna, não muda
  regras, distância de visão, iluminação ou qualquer mecânica que vocês já conhecem.
- Nenhuma ação necessária da parte de vocês - é tudo por trás dos panos.

## Por que isso importa

Esse sistema também estava usando um limite antigo que fazia sentido no modo local (split-screen,
até 4 jogadores na mesma tela), mas que não tinha motivo pra existir num servidor dedicado com
dezenas de jogadores. Removemos essa limitação e ajustamos o sistema para escalar de acordo com o
hardware real do servidor.

Como sempre, ficamos de olho na telemetria interna para garantir que a mudança está estável antes
e depois de picos de jogadores. Se alguém notar qualquer comportamento estranho relacionado a
zumbis "não vendo" ou "vendo demais" o personagem, avisem no canal de bugs.
