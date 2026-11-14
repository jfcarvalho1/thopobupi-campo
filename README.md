# ThopoBupi Campo

App Android de levantamento topográfico para a caderneta **UniStrong P9IV** com o receptor **UniStrong G970II Pro**.
Gratuita e de código aberto.

## Instalar na caderneta
1. No navegador da P9IV abre:
   `https://github.com/jfcarvalho1/thopobupi-campo/releases/latest/download/ThopoBupi_Campo.apk`
2. Abre o ficheiro descarregado e autoriza "instalar apps desconhecidas" quando o Android pedir.
3. As versões novas instalam-se por cima e mantêm os pontos.

## O que faz
- **Ligar**: activa o Bluetooth, liga ao G970II (perfil série) e à ReNEP por NTRIP (portas 2101, 2106 e 2102 à escolha).
- Estado FIX / FLOAT / autónomo, satélites, precisão (GST) e idade RTK.
- **E · N · Z em ETRS89 / PT-TM06 (EPSG:3763)**; Z = cota da antena − altura do bastão.
- Mapa com o **OrtoSat 2023 da DGT** e as **RGG públicas do BUPi** (só para ver, sem edição), com cópia local para usar sem rede.
- Levantamento desenhado a vermelho em tempo real; códigos de linha com `ST`, `END` e `CLO`.
- **Regra dos 5 cm**: erro total = √(H² + V²); pergunta sempre antes de gravar e assinala os pontos fora da tolerância. Sem cota, não grava.
- Medir (uma época) e Medir + (média de épocas).
- Implantar (ponto ou coordenadas E/N), COGO (distância, rumo, desnível, áreas e perímetros).
- Exportação **TXT (P,N,E,Z,D)** e **DXF R12** para `Transferências/ThopoBupi`.
- Teclas da P9IV: F1–F5 escolhem os primeiros cinco códigos, Enter mede, `*` exporta.

## Ainda não faz
- Compensação de inclinação (IMU) e laser: dependem do protocolo do fabricante do G970II.

## Receptor
Na app do fabricante, deixa activa a saída NMEA por Bluetooth (GGA e GST, 1 Hz) e fecha essa app antes de carregar em Ligar.
