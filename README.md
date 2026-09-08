# Y10 Intercom Bridge — experimental

Aplicativo Android experimental para o cenário:

`microfone do Y10 -> Redmi -> reprodução Bluetooth`

O serviço usa `foregroundServiceType="microphone"` para tentar continuar ativo com a tela bloqueada.

## Importante

Android normalmente permite apenas **um dispositivo Bluetooth de comunicação/SCO ativo por vez**. O app tenta selecionar um Y10 como dispositivo de comunicação para captar o microfone, mas deixa a saída do `AudioTrack` sem dispositivo fixo para dar ao HyperOS a chance de duplicar a reprodução nos dois Y10, caso o recurso de áudio simultâneo do telefone esteja ativo.

Portanto:
- funcionamento com **um Y10 como microfone** é tecnicamente mais provável;
- reprodução nos **dois Y10** depende do roteamento do Redmi/HyperOS;
- os **dois microfones simultâneos** podem continuar impossíveis por limitação do Bluetooth clássico/Android.

## Como compilar no Android Studio

1. Abra esta pasta no Android Studio atual.
2. Aguarde o Gradle sincronizar.
3. Build > Build APK(s).
4. O APK será gerado em `app/build/outputs/apk/debug/app-debug.apk`.

## Como testar no telefone

1. Pareado/conecte os dois Y10 ao Redmi.
2. No Bluetooth do Redmi, deixe ambos habilitados para áudio.
3. Abra o app e conceda Microfone, Bluetooth e Notificações.
4. Toque em **INICIAR INTERCOM**.
5. Fale no microfone do Y10.
6. Verifique se o outro Y10 reproduz a voz.
7. Bloqueie a tela e repita o teste.
8. Se parar, coloque o app em **Sem restrições** de bateria e permita inicialização em segundo plano no HyperOS.

Teste parado, sem pilotar enquanto configura o telefone.
