# Guide du système de commandes

Ce document explique comment utiliser le système de commandes du bot Discord.

## Table des matières

1. [Vue d'ensemble](#vue-densemble)
2. [Configuration](#configuration)
3. [Création de commandes](#création-de-commandes)
4. [Enregistrement de commandes](#enregistrement-de-commandes)
5. [Options de commande](#options-de-commande)
6. [Sous-commandes](#sous-commandes)
7. [Exécution de commandes](#exécution-de-commandes)
8. [Réponses aux commandes](#réponses-aux-commandes)
9. [Commandes système](#commandes-système)
10. [Gestion des permissions](#gestion-des-permissions)
11. [Synchronisation avec Discord](#synchronisation-avec-discord)
12. [Cooldowns](#cooldowns)
13. [Catégories de commandes](#catégories-de-commandes)

## Vue d'ensemble

Le système de commandes permet de créer et d'exécuter des commandes dans le bot Discord. Il prend en charge à la fois
les commandes slash (/) et les commandes textuelles préfixées. Ce système est conçu pour être facile à utiliser et
extensible.

Les principales fonctionnalités sont :

- Commandes slash et textuelles unifiées
- Options typées avec validation
- Sous-commandes et groupes
- Permissions
- Cooldowns
- Catégories dynamiques
- Support i18n (internationalisation)

## Configuration

Le système de commandes est configuré dans le fichier `config.yml` :

```yaml
commands:
  enabled: true  # Active ou désactive le système de commandes
  default-prefix: "!"  # Préfixe par défaut pour les commandes textuelles
```

Chaque guilde peut avoir son propre préfixe, qui est stocké dans la base de données.

## Création de commandes

### Utilisation du CommandBuilder

La façon la plus simple de créer une commande est d'utiliser le `CommandBuilder` :

```java
Command command = commandService.newCommand()
    .name("ping")
    .description("Ping the bot")
    .category("Utility")
    .aliases("p", "pong")
    .guildOnly(false)
    .executor((context, cmd) -> {
        context.reply("Pong!");
        return CommandResult.success();
    })
    .build();
```

### Utilisation de l'API Record

Pour plus de contrôle, vous pouvez utiliser directement `SimpleCommand` :

```java
Command command = new SimpleCommand(
    "ping",                 // name
    "Ping the bot",         // description
    "Utility",              // category
    List.of(),              // options
    List.of(),              // subcommands
    null,                   // group
    null,                   // permission
    null,                   // translationKey
    Set.of("p", "pong"),    // aliases
    false,                  // guildOnly
    Set.of(),               // guildIds
    false,                  // isSubcommand
    null,                   // parent
    true,                   // enabled
    0,                      // cooldown
    (context, cmd) -> {     // executor
        context.reply("Pong!");
        return CommandResult.success();
    }
);
```

## Enregistrement de commandes

Pour enregistrer une commande, utilisez la méthode `registerCommand` du `CommandService` :

```java
commandService.registerCommand(command, plugin);
```

ou avec le CommandBuilder :

```java
commandService.registerCommand(plugin, builder -> {
    builder.name("ping")
           .description("Ping the bot")
           .category("Utility")
           .executor((context, cmd) -> {
               context.reply("Pong!");
               return CommandResult.success();
           });
});
```

## Options de commande

Vous pouvez ajouter des options à une commande pour récupérer des entrées utilisateur.

### Types d'options

Les types d'options disponibles sont :

- `STRING` : Texte
- `INTEGER` : Nombre entier
- `BOOLEAN` : Booléen (vrai/faux)
- `USER` : Utilisateur Discord
- `CHANNEL` : Salon Discord
- `ROLE` : Rôle Discord
- `MENTIONABLE` : Utilisateur, rôle ou salon mentionnable
- `NUMBER` : Nombre à virgule flottante
- `ATTACHMENT` : Fichier joint

### Ajout d'options

```java
Command command = commandService.newCommand()
    .name("echo")
    .description("Echo a message")
    .category("Utility")
    .stringOption("message", "The message to echo", true)
    .integerOption("times", "Number of times to repeat", false, 1, 10)
    .executor((context, cmd) -> {
        String message = context.getRequiredOption("message");
        int times = context.getOption("times", 1);
        
        for (int i = 0; i < times; i++) {
            context.reply(message);
        }
        
        return CommandResult.success();
    })
    .build();
```

### Validation d'options

```java
.stringOption("name", "Your name", true, name -> name.length() >= 3)
```

### Choix d'options

```java
.stringOption("color", "Choose a color", true, 
    OptionChoice.of("Red", "red"),
    OptionChoice.of("Green", "green"),
    OptionChoice.of("Blue", "blue")
)
```

### Autocomplétion

```java
.stringOption("language", "Programming language", true, 
    input -> {
        List<String> languages = List.of("Java", "JavaScript", "Python", "C#", "C++");
        return languages.stream()
            .filter(lang -> lang.toLowerCase().contains(input.toLowerCase()))
            .map(lang -> OptionChoice.of(lang, lang))
            .collect(Collectors.toList());
    }
)
```

## Sous-commandes

Vous pouvez créer des sous-commandes :

```java
Command command = commandService.newCommand()
    .name("admin")
    .description("Administrative commands")
    .category("Admin")
    .subcommand(sub -> {
        sub.name("kick")
           .description("Kick a user")
           .userOption("user", "The user to kick", true)
           .stringOption("reason", "Reason for kick", false)
           .executor((context, cmd) -> {
               // Code d'exécution pour la sous-commande kick
               return CommandResult.success();
           });
    })
    .subcommand(sub -> {
        sub.name("ban")
           .description("Ban a user")
           .userOption("user", "The user to ban", true)
           .stringOption("reason", "Reason for ban", false)
           .integerOption("days", "Number of days to delete messages", false, 0, 7)
           .executor((context, cmd) -> {
               // Code d'exécution pour la sous-commande ban
               return CommandResult.success();
           });
    })
    .build();
```

Vous pouvez également regrouper les sous-commandes :

```java
Command command = commandService.newCommand()
    .name("config")
    .description("Configuration commands")
    .category("Admin")
    .group("server")
    .subcommand(sub -> {
        sub.name("name")
           .description("Change server name")
           .stringOption("name", "New server name", true)
           .executor((context, cmd) -> {
               // Code d'exécution
               return CommandResult.success();
           });
    })
    .subcommand(sub -> {
        sub.name("icon")
           .description("Change server icon")
           // Restreint le sélecteur de fichiers Discord aux images
           // (catégories "image"/"video"/"audio" ou extensions sans point, ex. "svg" ; 10 max)
           .attachmentOption("icon", "New server icon", true, "image")
           .executor((context, cmd) -> {
               // Code d'exécution
               return CommandResult.success();
           });
    })
    .build();
```

## Exécution de commandes

Les commandes sont exécutées par le système lorsqu'un utilisateur les invoque. Le système :

1. Analyse la commande et extrait les options
2. Crée un `CommandContext` contenant les informations de la commande
3. Vérifie les permissions et les cooldowns
4. Exécute la commande via sa méthode `execute`
5. Renvoie le résultat à l'utilisateur

## Réponses aux commandes

Le `CommandContext` fournit plusieurs méthodes pour répondre à l'utilisateur :

```java
// Réponse simple
context.reply("Hello, world!");

// Réponse avec embed
EmbedBuilder embed = new EmbedBuilder()
    .setTitle("Hello")
    .setDescription("World");
context.replyEmbed(embed);

// Réponses formatées
context.replySuccess("Command executed successfully!");
context.replyInfo("Here's some information.");
context.replyWarning("This is a warning.");
context.replyError("An error occurred.");

// Réponses différées pour les commandes longues
context.deferReply();
// ... code long ...
context.reply("Finally done!");

// Réponses éphémères (visibles uniquement pour l'utilisateur)
context.setEphemeral(true);
context.reply("Only you can see this.");
```

## Commandes système

Le système fournit plusieurs commandes système :

- `help` : Affiche des informations sur les commandes disponibles
- `version` : Affiche la version du bot
- `shutdown` : Arrête le bot (réservé aux opérateurs)
- `perm <set|unset|list|nodes> [permission] [userId] [value] [scope]` : Gère les surcharges de permissions des utilisateurs, persistées dans le storage (console, ou un opérateur depuis Discord). `scope` = `guild` (défaut depuis un serveur) ou `global`. `perm nodes` liste les permissions enregistrées par les plugins et leur défaut.

Les **opérateurs** reçoivent toutes les permissions dont le défaut est `OP` (ex. volume et actions admin du plugin musique). Sont opérateurs : les IDs de `permissions.operators` dans `config.yml`, et — dans un serveur — son propriétaire et les membres ayant la permission Discord `ADMINISTRATOR`.

## Gestion des permissions

Les commandes peuvent spécifier une permission requise :

```java
.permission("discobocor.admin.kick")
```

Le système vérifie automatiquement si l'utilisateur a la permission avant d'exécuter la commande.

## Synchronisation avec Discord

Lorsque vous enregistrez une commande, le système la synchronise automatiquement avec Discord pour qu'elle apparaisse
dans l'interface utilisateur. Vous pouvez également forcer la synchronisation :

```java
commandService.synchronizeCommands();
commandService.synchronizeGuildCommands(guild);
commandService.synchronizeGlobalCommands();
```

## Cooldowns

Vous pouvez définir un cooldown pour une commande, qui empêchera un utilisateur de l'utiliser trop souvent :

```java
.cooldown(10) // 10 secondes
```

## Catégories de commandes

Les commandes sont organisées en catégories, qui sont utilisées dans la commande `help`. Vous pouvez créer autant de
catégories que vous le souhaitez :

```java
.category("Music")
.category("Admin")
.category("Fun")
```

Les catégories sont dynamiques et sont automatiquement disponibles dans la commande `help`.
