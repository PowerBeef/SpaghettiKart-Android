# Custom Audio
To replace a sequences you should only create a zip file with sequences to replace at the same path as mk64.o2r. Only wav, mp3, ogg and flac are supported.
## Example:
You want to change sound/sequences/main_menu then you just need to create a zip file with the following structure:
```
textures_pack.zip
└── sound
    └── sequences
        └── main_menu.mp3
```

## Future plans
* Make name more meaningful and uniform. (example: change `seq_16`). You can suggest name or make a PR to help rename them/organise them.
* Support samples.
* Create a tool to convert sequences and samples.