# SpaghettiKart AI Agents & Automation Systems

## Overview

SpaghettiKart is a Mario Kart 64 port that includes several AI and automation systems for enhanced gameplay, development workflows, and multiplayer experiences. This document outlines the various agent-based systems implemented in the codebase.

## Table of Contents

1. [CPU Racing Agents](#cpu-racing-agents)
2. [Networking & Multiplayer Systems](#networking--multiplayer-systems)
3. [Build & CI/CD Automation](#build--cicd-automation)
4. [Development Tools & Scripts](#development-tools--scripts)
5. [Asset Processing Automation](#asset-processing-automation)

---

## CPU Racing Agents

### Core AI System

The CPU racing system provides intelligent computer-controlled opponents with sophisticated decision-making capabilities.

**Key Files:**
- `src/player_controller.c` - Main CPU control logic
- `src/code_80005FD0.c` - CPU strategy and decision making
- `src/update_objects.c` - CPU item generation and usage

### CPU Character Selection

The system includes predefined CPU character arrays for different player combinations:

```c
s16 cpu_forMario[] = { LUIGI, YOSHI, TOAD, DK, WARIO, PEACH, BOWSER, 0 };
s16 cpu_forLuigi[] = { MARIO, YOSHI, TOAD, DK, WARIO, PEACH, BOWSER, 0 };
// ... additional character arrays for all combinations
```

### CPU Movement & Navigation

**Functions:**
- `func_8002D028()` - CPU staging area movement
- `apply_cpu_turn()` - CPU steering control
- `control_cpu_movement()` - Main CPU movement logic

The CPU agents use waypoint-based navigation with intelligent pathfinding and collision avoidance.

### CPU Item Strategy System

**Strategy Types:**
```c
enum CpuItemStrategyEnum {
    CPU_STRATEGY_WAIT_NEXT_ITEM = 0,
    CPU_STRATEGY_ITEM_BANANA,
    CPU_STRATEGY_HOLD_BANANA,
    CPU_STRATEGY_DROP_BANANA,
    CPU_STRATEGY_ITEM_GREEN_SHELL,
    CPU_STRATEGY_HOLD_GREEN_SHELL,
    CPU_STRATEGY_THROW_GREEN_SHELL,
    CPU_STRATEGY_ITEM_RED_SHELL,
    CPU_STRATEGY_HOLD_RED_SHELL,
    CPU_STRATEGY_THROW_RED_SHELL,
    // ... additional strategies
};
```

**Item Generation:**
- `cpu_gen_random_item()` - Standard CPU item generation
- `hard_cpu_gen_random_item()` - Enhanced difficulty item generation

### CPU Difficulty Levels

The system supports multiple difficulty levels:
- **Standard CPU**: Basic AI with standard item usage
- **Hard CPU**: Enhanced AI with improved decision making and item strategies
- **Configurable**: Difficulty can be adjusted via `gHarderCPU` configuration

---

## Networking & Multiplayer Systems

### Network Architecture

**Key Files:**
- `src/networking/networking.h` - Network protocol definitions
- `src/networking/networking.c` - Network implementation
- `src/networking/replication.c` - Player state replication

### Network Client Management

```c
typedef struct {
    char username[NETWORK_USERNAME_LENGTH];
    s32 slot;
    s32 isPlayer;
    s32 isAI;                    // AI player flag
    s32 character;
    s32 hasAuthority;
} NetworkClient;
```

### Packet Types

The networking system supports various packet types for multiplayer coordination:
- `PACKET_JOIN` - Player joining
- `PACKET_LEAVE` - Player leaving
- `PACKET_PLAYER` - Player state updates
- `PACKET_ACTOR` - Actor synchronization
- `PACKET_OBJECT` - Object state replication

### AI in Multiplayer

The system supports AI players in multiplayer sessions through the `isAI` flag in `NetworkClient`, allowing for mixed human/AI races.

---

## Build & CI/CD Automation

### GitHub Actions Workflows

The project includes comprehensive CI/CD automation through GitHub Actions:

**Main Workflow (`main.yml`):**
- **Multi-platform builds**: Windows, macOS, Linux, Nintendo Switch
- **Asset generation**: Automated O2R file creation
- **Caching**: Build cache optimization with ccache
- **Artifact management**: Automated build artifact distribution

**Release Workflow (`release.yml`):**
- **Android builds**: Automated APK generation
- **Version management**: Automatic version bumping
- **Code signing**: Automated keystore management
- **Release creation**: GitHub release automation

### Build Automation Features

1. **Cross-platform compilation**
2. **Dependency management** (vcpkg, apt, brew)
3. **Asset processing** (O2R generation)
4. **Code formatting** (clang-format automation)
5. **Documentation generation** (Doxygen)

### Docker Integration

The project includes Docker-based builds for consistent cross-platform compilation:
- **Dockerfile**: Containerized build environment
- **Docker caching**: Optimized build times
- **Multi-architecture support**: ARM64 and x86_64

---

## Development Tools & Scripts

### Asset Processing Scripts

**Python Scripts:**
- `extract_assets.py` - Asset extraction and processing
- `progress.py` - Progress tracking and reporting
- `python_convert.py` - Asset format conversion
- `addr_to_sym.py` - Address to symbol conversion

### Build Scripts

**Shell Scripts:**
- `valgrind_callgrind.sh` - Memory profiling automation
- `rename_sym.sh` - Symbol renaming automation
- `test_blend.bat` - Blender integration testing

### Configuration Management

**Configuration Files:**
- `config.yml` - Main project configuration
- `vcpkg.json` - Dependency management
- `CMakeLists.txt` - Build system configuration

---

## Asset Processing Automation

### O2R Generation

The project includes automated asset processing for creating O2R (Open 2 Resource) files:

**Process:**
1. **Asset extraction** from ROM files
2. **Format conversion** to modern formats
3. **Compression and packaging** into O2R archives
4. **Validation and testing** of generated assets

### Torch Integration

The build system integrates with the Torch tool for asset processing:
- **Automated O2R creation** during build process
- **Asset validation** and integrity checking
- **Cross-platform asset compatibility**

---

## AI System Architecture

### Decision Making Framework

The CPU AI system uses a hierarchical decision-making approach:

1. **Strategic Level**: Race position and overall strategy
2. **Tactical Level**: Item usage and path selection
3. **Operational Level**: Steering, acceleration, and braking

### State Management

CPU agents maintain internal state for:
- **Current strategy** (item usage, positioning)
- **Path following** (waypoint navigation)
- **Collision avoidance** (obstacle detection)
- **Performance metrics** (lap times, positions)

### Performance Optimization

The AI system includes performance optimizations:
- **Frame-based updates** (alternating CPU updates)
- **Distance-based rendering** (LOD for AI calculations)
- **Caching** (path calculations, decision trees)

---

## Future Enhancements

### Potential AI Improvements

1. **Machine Learning Integration**
   - Neural network-based decision making
   - Adaptive difficulty based on player performance
   - Learning from player behavior patterns

2. **Advanced Pathfinding**
   - Dynamic path optimization
   - Real-time obstacle avoidance
   - Predictive collision detection

3. **Behavioral AI**
   - Personality-based racing styles
   - Adaptive strategies based on race conditions
   - Team-based AI coordination

### Automation Enhancements

1. **Testing Automation**
   - Automated gameplay testing
   - Performance regression testing
   - Cross-platform compatibility testing

2. **Asset Pipeline**
   - Automated asset optimization
   - Real-time asset validation
   - Dynamic asset loading

---

## Configuration

### CPU AI Settings

```c
// CPU difficulty configuration
CVarGetInteger("gHarderCPU", 0)  // Enable harder CPU difficulty

// CPU update frequency
gIncrementUpdatePlayer  // Alternating CPU updates for performance
```

### Network Settings

```c
#define NETWORK_MAX_PLAYERS 8
#define NETWORK_USERNAME_LENGTH 32
```

### Build Configuration

```cmake
# CMake configuration for AI systems
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_C_STANDARD 11)
```

---

## Conclusion

SpaghettiKart implements a comprehensive set of AI and automation systems that enhance both gameplay and development workflows. The CPU racing agents provide challenging opponents with sophisticated decision-making capabilities, while the build automation ensures consistent, high-quality releases across multiple platforms.

The modular architecture allows for easy extension and improvement of AI capabilities, making it well-suited for future enhancements in machine learning and advanced AI techniques.

---

*This document is automatically generated and should be updated as the AI systems evolve.*