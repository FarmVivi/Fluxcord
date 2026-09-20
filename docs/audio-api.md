# Audio API pour Fluxcord

Cette documentation décrit l'API audio implémentée dans Fluxcord, qui permet aux plugins de gérer les flux audio
entrants et sortants avec des fonctionnalités avancées comme le mixage et la gestion des priorités.

## Caractéristiques

- **Multi-sources** : Permet à plusieurs plugins d'envoyer de l'audio simultanément
- **Mixage intelligent** : Mixe automatiquement les sources audio avec des volumes individuels
- **Système de priorités** : Les sources à haute priorité peuvent atténuer temporairement les autres
- **Fondus automatiques** : Transitions douces lors des changements de priorité
- **Multi-récepteurs** : Plusieurs plugins peuvent recevoir l'audio en même temps
- **Optimisations performantes** : Mode bypass pour une seule source, mixage efficace pour plusieurs

## Comment utiliser l'API

### 1. Accéder au service audio

Le service audio est accessible via le `PluginContext` :

```java
@Override
public void onLoad(PluginContext context) {
    super.onLoad(context);
    
    context.getAudioService();
}
```

### 2. Enregistrer un handler d'envoi audio

Pour envoyer de l'audio, vous devez implémenter l'interface `AudioSendHandler` de JDA et l'enregistrer auprès du service
audio :

```java
// Crée votre handler d'envoi audio
AudioSendHandler mySendHandler = new MyAudioSendHandler();

// Enregistre le handler avec volume 80% et priorité 50
audioService.registerSendHandler(guild, this, mySendHandler, 80, 50);
```

Le handler d'envoi doit implémenter les méthodes suivantes :

```java
public class MyAudioSendHandler implements AudioSendHandler {
    @Override
    public boolean canProvide() {
        // Retourne true si ce handler peut fournir de l'audio
    }
    
    @Override
    public ByteBuffer provide20MsAudio() {
        // Retourne un ByteBuffer contenant 20ms d'audio (PCM 48kHz 16-bit stéréo)
    }
    
    @Override
    public boolean isOpus() {
        // Retourne true si l'audio est déjà encodé en Opus, false pour PCM
    }
}
```

### 3. Enregistrer un handler de réception audio

Pour recevoir de l'audio, vous devez implémenter l'interface `AudioReceiveHandler` de JDA et l'enregistrer auprès du
service audio :

```java
// Crée votre handler de réception audio
AudioReceiveHandler myReceiveHandler = new MyAudioReceiveHandler();

// Enregistre le handler
audioService.registerReceiveHandler(guild, this, myReceiveHandler);
```

Le handler de réception doit implémenter les méthodes suivantes :

```java
public class MyAudioReceiveHandler implements AudioReceiveHandler {
    @Override
    public boolean canReceiveCombined() {
        // Retourne true si ce handler veut recevoir l'audio combiné
    }
    
    @Override
    public boolean canReceiveUser() {
        // Retourne true si ce handler veut recevoir l'audio par utilisateur
    }
    
    @Override
    public void handleCombinedAudio(CombinedAudio combinedAudio) {
        // Traite l'audio combiné de tous les utilisateurs
    }
    
    @Override
    public void handleUserAudio(UserAudio userAudio) {
        // Traite l'audio d'un utilisateur spécifique
    }
    
    @Override
    public boolean canReceiveEncoded() {
        // Retourne true si ce handler veut recevoir l'audio Opus encodé
    }
    
    @Override
    public void handleEncodedAudio(OpusPacket opusPacket) {
        // Traite l'audio Opus encodé
    }
}
```

### 4. Gérer le volume

Vous pouvez modifier le volume d'un handler d'envoi à tout moment :

```java
// Définit le volume à 75%
audioService.setVolume(guild, this, 75);
```

### 5. Configurer les priorités

Par défaut, les priorités sont définies comme suit :

- Priorité : 0 (la plus basse) à 100 (la plus haute)
- Seuil de priorité : 70 (les sources avec une priorité ≥ 70 atténuent les autres)

Vous pouvez modifier le seuil de priorité pour une guilde :

```java
// Définit le seuil de priorité à 80
audioService.setPriorityThreshold(guild, 80);
```

L'atténuation (ducking) est configurable dans `config.yml`, section `audio` :

```yaml
audio:
  fade-duration-ms: 200   # durée du fondu quand une source prioritaire démarre / s'arrête
  ducking-level: 20       # volume (0-100) des autres sources pendant ce temps ; 0 = muettes
```

Une source seule (PCM ou Opus) est relayée sans mixage ; son volume et le fondu sont appliqués pendant la
conversion vers le format JDA, sans passe supplémentaire. Le volume ne s'applique pas aux sources Opus
(déjà encodées).

### 6. Nettoyage des ressources

Le service audio nettoie automatiquement les handlers lorsqu'un plugin est désactivé, mais vous pouvez le faire
manuellement :

```java
// Désenregistre un handler d'envoi
audioService.deregisterSendHandler(guild, this);

// Désenregistre un handler de réception
audioService.deregisterReceiveHandler(guild, this);

// Ferme une connexion audio pour une guilde
audioService.closeAudioConnection(guild);

// Ferme toutes les connexions audio pour un plugin
audioService.closeAllConnectionsForPlugin(this);
```

## Événements audio

Le système émet plusieurs événements que vous pouvez écouter :

```java
@EventHandler
public void onAudioSendHandlerRegistered(AudioSendHandlerRegisteredEvent event) {
    // Un handler d'envoi a été enregistré
}

@EventHandler
public void onAudioSendHandlerRemoved(AudioSendHandlerRemovedEvent event) {
    // Un handler d'envoi a été désenregistré
}

@EventHandler
public void onAudioReceiveHandlerRegistered(AudioReceiveHandlerRegisteredEvent event) {
    // Un handler de réception a été enregistré
}

@EventHandler
public void onAudioReceiveHandlerRemoved(AudioReceiveHandlerRemovedEvent event) {
    // Un handler de réception a été désenregistré
}

@EventHandler
public void onAudioVolumeChanged(AudioVolumeChangedEvent event) {
    // Le volume d'un handler a changé
}

@EventHandler
public void onAudioFrameMixed(AudioFrameMixedEvent event) {
    // Un frame audio a été mixé
    int sources = event.getActiveSourceCount();
    boolean bypass = event.isBypassMode();
    boolean hasAudio = event.containsAudio();
}
```

## Exemple complet

Consultez la classe `AudioExamplePlugin.java` dans le package `fr.farmvivi.fluxcord.examples.audio` pour un exemple
complet d'utilisation de l'API audio.

## Performances et considérations techniques

- Convention de format interne: fournissez du PCM Little-Endian 48 kHz, 16-bit, stéréo, par trames de 20 ms (3840
  octets). Le pipeline convertit en Big-Endian uniquement à la frontière JDA.
- Le système utilise un mode "bypass" efficace lorsqu'une seule source est active.
- Le mixage PCM est optimisé (somme en int, clipping dur) et évite les copies inutiles.
- Les fondus sont calculés de manière progressive pour des transitions douces.
- Le système est thread-safe et adapté aux environnements multi-threads.
- Le décodage/encodage Opus est géré automatiquement par JDA côté envoi si `isOpus()==false`.

## Restrictions

- Le système utilise uniquement les fonctionnalités audio de JDA, sans dépendances externes
- Chaque frame audio a une durée fixe de 20ms (standard pour Discord).
- Format attendu côté handlers: PCM 48kHz 16-bit stéréo, Little-Endian (ou Opus encodé si `isOpus()==true`).