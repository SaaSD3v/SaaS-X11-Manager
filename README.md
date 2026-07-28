# X11 Manager

App Android nativo para gerenciar containers DroidSpaces com X11 (Termux:X11).

## Funcionalidades

- **Lista containers** DroidSpaces automaticamente
- **Verifica pre-requisitos** (Root, Termux, Termux:X11, DroidSpaces)
- **Aplica patches** de X11 no rootfs do container
- **Inicia Loader** X11 como root
- **Inicia container** com XFCE
- **Abre Termux:X11** automaticamente
- **Log em tempo real** de todas as operacoes

## Requisitos

- Android com root (KernelSU)
- Termux instalado
- Termux:X11 APK instalado
- DroidSpaces com container

## Build

```bash
# Build debug
./gradlew assembleDebug

# Build release
./gradlew assembleRelease
```

## CI/CD

O GitHub Actions constroi o APK automaticamente a cada push. Configure os secrets:

- `RELEASE_KEYSTORE`: Keystore em base64
- `KEYSTORE_PASSWORD`: Senha do keystore
- `KEY_ALIAS`: Alias da chave
- `KEY_PASSWORD`: Senha da chave

## Licenca

MIT
