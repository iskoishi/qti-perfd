# Qualcomm perf HAL：未鉴权的 root 性能服务可被普通应用驱动，写入任意目标进程的内核节点

# Qualcomm perf HAL: an unauthenticated root perf service can be driven by any unprivileged app to write privileged kernel nodes for an arbitrary target process

> **主张范围**
>
> 1. **鉴权缺失（已真机验证）**：任意已安装应用、无任何权限、无用户交互，经 `vendor.perfservice` 让 **root** 进程执行内核节点写入，目标 pid 由调用者选择，包括其他第三方应用。
> 2. **越界写（代码级确证，出货二进制中即有）**：`ResourceQueue::GetNode` 判界漏等号，可越界写一个 ~40 字节结构体。
>
> **明确不主张**：**不主张提权**。未做堆布局 → 代码执行，不声称可利用。
>
> **两个必须先看的前提**：
>
> - 越界写仅在 perf 配置声明 `TotalNumCores ≤ 7` 的平台上可达。**8 核机型（含本报告测试设备）经该 opcode 路径不可达**，因此你在那类设备上复现失败是预期的。
> - 本报告的破坏上限是**功耗与发热**，不是代码执行。
>
> 报告日期 2026-10-05 · 公开日期 2026-10-06 · 完整范围见 [§8](#8-披露信息)

---

## 1. 概要

搭载 Qualcomm 性能栈的 Android 设备上有两级服务：

- `vendor.perfservice` —— `uid 1000`（system），SELinux 域 `vendor_perfservice`
- `vendor.qti.hardware.perf@2.2-service` —— **`root`**，SELinux 域 `vendor_hal_perf_server`

服务端**完全不做调用者身份检查**：native 栈里没有任何 `getCallingUid()`、没有权限校验、没有签名校验。设计里唯一的访问控制是 **客户端侧**的——Qualcomm 的 Java 包装 `QPerformance.jar` 在发起事务前做一次签名比较。应用只要自己构造 binder 事务就能绕开，因为校验从来不在服务端发生。

于是：**任意普通已安装应用**都可以经原始 binder 调用 `IPerfManager::perfLockAcquire`，让 root HAL 把调用者选定的值写入调用者选定的**目标进程**内核节点。在验证设备上，这表现为对 shell 进程和一个**第三方应用**的 `/proc/<victim_pid>/sched_boost` 写入 `3`，而调用应用自己连这个文件都打不开（`ENOENT`）。

---

## 2. 影响与严重程度

### 2.1 可修改的能力

经一条 root 持有的服务链路，无需任何权限、无需用户交互，普通应用可以：

| 能力 | 目标 | 状态 |
|---|---|---|
| 改写调度提升态 `sched_boost` | **任意** pid（含其他应用、系统组件） | 已实测 |
| 改写 GPU 上下文态 `foreground`/`background` | 任意 pid，降低他方渲染优先级 | 节点存在，未实测 |
| 改写全局调度器 / cpuset / 电源旋钮 | 整机 | 已实测（见 2.2） |
| 释放其他客户端的 perf 锁 | 任意 handle | 无所有权检查 |

### 2.2 实测破坏（已验证）

请求：opcode `0x40800000`（节点模板 `/sys/module/msm_performance/parameters/cpu_min_freq`，整机 CPU 频率地板），值为高频率档位，duration 60 秒。

| 阶段 | `cpu0/cpufreq/scaling_min_freq` |
|---|---|
| 基线 | **576 000** |
| 应用请求期间 | **1 516 800**（×2.6） |
| 60 秒锁过期后 | 576 000（恢复） |

对照：该底层节点 shell 读取都 `Permission denied`，普通应用没有任何直接手段写入。

**为什么这是问题**：CPU 地板是整机范围的。抬上去并**反复续期**（没有任何机制节流调用者）即可让设备永不 idle 到抬升档位以下——持续的功耗与发热，挤占其他应用的热预算，**用户完全无感知**（无通知、无 UI 变化）。重启可清除，但应用运行期间可无限维持。

CVSS 3.1：`AV:L/AC:L/PR:L/UI:N/S:U/C:N/I:H/A:H` → **7.1 (High)**

---

## 3. 漏洞原因

### 3.1 服务端没有任何调用者身份检查

- `PerfService.cpp`：`getCallingPid()` 出现 **6** 处，**全部**用于句柄簿记；`getCallingUid()`、权限校验、签名校验 **0** 处。
- 两份同源 ELF 构建独立确证（见 §4.3）：动态符号表含 `getCallingPid`（PLT 槽 + 4 个调用点），**无** `getCallingUid` / `IPCThreadState` 访问检查。

### 3.2 唯一的鉴权在客户端

`QPerformance.jar`（`com.qualcomm.qti.Performance` / `IPerfManager`）在发起事务前做**客户端侧签名比较**。调用方自己构造事务即可完全绕过——校验不在服务端，服务端无从得知调用者是谁。

### 3.3 调用者数据直接进入内核节点路径

`IPerfManager::perfLockAcquire(int duration, int len, int[] boostsList)`：

- `boostsList` 是调用者提供的扁平 `(opcode, value)` 对。
- HAL 把 `opcode` 对到 `/vendor/etc/perf/*.xml` 建出的表，再把**调用者提供的 value 插入 printf 风格的节点路径**：

```c
// mp-ctl/OptsHandler.cpp — sched_task_boost()
snprintf(node_path, NODE_MAX, d.sysfsnode_path[idx], r.value);   // r.value == 调用者的值
snprintf(tmp_s, NODE_MAX, "%d", TASK_BOOST_STRICT_MAX);
update_node_param(d.node_type[idx], node_path, tmp_s, strlen(tmp_s));   // 以 root 身份写
```

- `Request::OverrideClientValue()` 只针对 opcode `0xA` 和 `0xE`（值 `1`）把值换成调用者自己的 tid；**其余 opcode 原样透传**。
- 无白名单，无上下限钳制。

**净效果：调用者选目标 pid。**

### 3.4 受影响节点类别（取自设备自身的 `/vendor/etc/perf/` 表）

| opcode | 节点 | value 语义 |
|---|---|---|
| `0x40C80000`（3/0x20），`0x40C8xxxx`（3/0x44、3/0x45） | `/proc/%d/sched_boost` | **目标 pid** |
| 3/0x52 | `/proc/%d/sched_low_latency` | **目标 pid** |
| 0x40000000 族 GPU 0x8/0x9 | `/sys/class/kgsl/kgsl/proc/%d/state` | **目标 pid**，值 `foreground`/`background` |
| 20+ 条（major 3 / major 1） | `/proc/sys/kernel/sched_*`、`/proc/sys/walt/*` | 全局调度旋钮 |
| 3/F、3/10 … | `/dev/cpuset/*/cpus` | 全局 cpuset 掩码 |

---
### 3.5 第二个发现：root HAL 中的越界写（出货二进制中即有）

审计 HAL 加载进**其自身 root 进程**的库时发现的独立问题。

**位置**：`ResourceQueue::GetNode(Resource&)`，源码 `mp-ctl/ResourceQueues.cpp`

```c
q_node *ResourceQueue::GetNode(Resource &resObj) {
    TargetConfig &tc = TargetConfig::getTargetConfig();
    unsigned short idx = resObj.qindex;

    if ((idx >= MAX_MINOR_RESOURCES) ||
        (resObj.core < 0) || (resObj.core > tc.getTotalNumCores()))   // <-- 漏等号
        return NULL;
    ...
        nodes = (q_node *)calloc(tc.getTotalNumCores(), sizeof(q_node));
    ...
        tmp = &resource_qs[idx].right[resObj.core];   // <-- 按 core 直接索引
```

判界用 `>`，但数组按 `TotalNumCores` 个元素分配。`core == TotalNumCores` 时校验通过，索引落在分配区末尾之后一个元素处，随后被写入（`current->handle = req; current->resource = resObj;`）。每元素约 40 字节。

**确认在出货二进制中**，非仅上游源码——测试设备 `/vendor/lib64/libqti-perfd.so`，导出符号 `ResourceQueue::GetNode(resource&)`，`0x1aeb8`，180 字节：

```asm
1aee4:  ldr  x10, [x10, #2184]    ; current target
1aeec:  ldrh w20, [x10, #16]      ; w20 = TotalNumCores
1aef0:  cmp  w20, w9              ; TotalNumCores  vs  resObj.core
1aef4:  b.cs 1af0c                ; TotalNumCores >= core 即放行   <-- 漏等号
...
1af38:  mov  x0, x20
1af3c:  bl   calloc               ; calloc(TotalNumCores, sizeof(q_node))
1af20:  umaddl x0, w9, x10, x8    ; &right[core] → core == TotalNumCores 时越界
```

**可达性**：`core` 来自调用者提供 opcode 的 3 位字段 `(op >> 4) & 7`，因此最大可到 7。⇒ 该缺陷**仅在 perf 目标配置声明 `TotalNumCores ≤ 7` 的平台上可达**。测试设备 `TotalNumCores = 8`（读自设备自身的 `/vendor/etc/perf/targetconfig.xml`），`core == 8` 不可能发生，**本机不可达**——报告它是因为 Qualcomm 有搭载同款 HAL/optab 代码的 4 核与 6 核器件，供对照。

**可利用性**：单个约 40 字节、部分受调用者影响的结构体越界写，发生在 root 进程内，随后仍通过同一指针读取。走到代码执行需要堆整理——**这一步未尝试，也不声称**。入口与 §5 完全相同（任意应用、无权限、无用户交互）。

---

## 4. 受影响范围

### 4.1 为什么应用打不到 HAL 直连路径（设计上的门）

`vendor.qti.hardware.perf::IPerf` 映射到 SELinux 类型 `vendor_hal_perf_hwservice`，该类型被列入 `protected_hwservice`（`vendor_sepolicy.cil:332`），平台策略含：

```
(neverallow untrusted_app_all protected_hwservice (hwservice_manager (find)))   # plat_sepolicy.cil:19057
(neverallow untrusted_app     protected_hwservice (hwservice_manager (find)))   # plat_sepolicy.cil:19063
```

所以直连路径是正确关闭的。**`vendor.perfservice` 因此是唯一通道**——而它恰好是 app 可达且未鉴权的那个组件。

### 4.2 为什么应用打得到 perfservice

```
(allow untrusted_app_all app_api_service (service_manager (find)))          # 平台策略
(allow appdomain binderservicedomain (binder (call transfer)))              # 平台策略
vendor.perfservice -> type vendor_perf_service, attribute app_api_service   # system_ext 策略
vendor_perfservice -> attribute binderservicedomain
```

### 4.3 跨厂商同源证据

在其他 OEM 的 vendor dump 中找到了**同源代码**的同一守护进程——OnePlus 7T（`system_ext/bin/perfservice`，36,760 字节）与 ASUS ROG3（44,552 字节）；服务源码本身存在于多家 OEM 树共用的 Qualcomm 专有 drops 中（`commonsys/perf-core/perfservice`、`commonsys/android-perf/perfservice`，HAL 位于 `android-perf/perf-hal`，optab 位于 `android-perf/mp-ctl`）。

⇒ 这是**组件级**问题，不是单一 OEM 的集成缺陷。

---

## 5. 验证过程

| 项目 | 值 |
|---|---|
| 验证身份 | 普通已安装应用，**uid 10234**，无任何声明/持有权限 |
| 使用接口 | 仅原始 binder 事务，不经 Qualcomm 客户端库 |
| 是否提权 | 否 |
| 是否使用非常规接口 | 否 |
| 是否造成崩溃 | 否，测试期间无崩溃、无重启 |

### 5.1 可达性（只读）

```
[1] getService("vendor.perfservice")       → BinderProxy@...
[2] getInterfaceDescriptor()               → com.qualcomm.qti.IPerfManager
[3] transact(12 /* getPerfHalVer */)       → ok，返回 2.2（服务端执行了调用）
```

### 5.2 越权写入，与调用应用自身的权限对照

目标：shell 所属的辅助进程，`/proc/<victim_pid>/sched_boost`。

```
[4] 应用直接 open /proc/<victim_pid>/sched_boost 写入 → FileNotFoundException: ENOENT
    （调用者连路径都解析不出来）
[5] 应用 transact(4 /* perfLockAcquire */)，boostsList = { 0x40C80000, <victim_pid> }
                                                       → ok，返回 handle 1550971
[6] shell 读 /proc/<victim_pid>/sched_boost → 0（调用前基线）
[7] shell 读 /proc/<victim_pid>/sched_boost → 3（调用后）
```

**跨应用成立**：目标换为第三方应用的进程（`tv.danmaku.bili`，pid 4825），同一序列，基线 `0` → 调用后 `3`。

⇒ **应用做不到的事，被应用让 root 进程做了。**

### 5.3 整机级旋钮

```
[8]  opcode 0x40800000，值 = 高频率档位，duration = 60 s
    /sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq
    576000 → 1516800
[9]  60 秒锁过期后                          → 576000
[10] shell cat /sys/module/msm_performance/parameters/cpu_min_freq
                                              → Permission denied
```

⇒ 该节点连 shell 都读不了，普通应用无任何直接写入手段，值仍因应用的请求而改变 ⇒ 写作者是 root HAL。

### 5.4 同一组件的第二个未鉴权路径

`perfLockAcquire` 之外，同一守护进程还暴露另一条 app 可达且**同样无调用者校验**的请求，通向另一个特权消费者。

- 事务 5 是 `perfUXEngine_events(int opcode, int pid, String16 pkg_name, int lat)`——**四个参数全部调用者可控**。
- `PerfService.cpp` 转字符串后原样转发：`perf_ux_engine_events(opcode, pid, pkg_name, lat)`，无 opcode 白名单、无长度检查、无签名检查、无 `getCallingUid()`。
- 到达 **IOP** HIDL 服务（`IIop::uxEngine_events`）经 `ioclient.cpp`，四参数不变塞入 `iop_msg_t{cmd=3, opcode, pid, pkg_name, lat}`。
- 落点：IOP 用户态库（`io-p.cpp`、`uxe_server`）把值写入 `/data/vendor/iop/` 下的 SQLite。其中 **`pkg_name` 不经转义直接插入 SQL 语句**（`dblayer.cpp`）——同一条未鉴权路径上可达单语句 SQL 注入。
- IOP 服务同样**无** `getCallingUid` / `getCallingPid` / 权限 / `IPCThreadState` 调用点（全源码集 nil 检查），`pkg_name` 也不绑定调用者身份。

真机测量：

```
[11] 应用 transact(5 /* perfUXEngine_events */)，opcode = 2、3、4
     → ok = true，无异常，返回值 = -1（三个 opcode 一致）
```

两个对准确评估危害有影响的结论：

- **可达性已在线确认**：请求被守护进程接受，**完全没有任何鉴权拒绝**——与 §3 同一个缺陷，端到端观察到。
- **本机构建上落点未到达**：`-1` 表明 UX engine 路径被门控关闭。该设备上 `vendor.iop.enabled` 与 `vendor.iop.enable_uxe` 均未设置（代码默认值，`enable_uxe` 默认为关），持久属性 `persist.sys.enable_ioprefetch` 为 `false`。⇒ §5.4 的 SQL 落点需要该路径被启用的构建。

⇒ 因为返回值能区分"被门控"与"已执行"，**该路径未被验证到达数据库**；仅确认了可达性与服务端鉴权缺失。

---
## 6. 修复建议

### 6.1 服务端鉴权（主修复）

在 `PerfService`（并在 HAL 内做纵深防御）读取 `IPCThreadState::self()->getCallingUid()`，然后二选一：

- (a) 拒绝特权白名单之外的调用者；或
- (b) 把请求约束到调用者自身进程，**永不接受调用者传入的任意 pid/tid**。

补丁见 [`patches/02-perfservice-caller-uid-gate.patch`](patches/02-perfservice-caller-uid-gate.patch)。

### 6.2 永不再把调用者值插入节点路径

对 `%d` 形式的节点，把值钳制到调用者自身的 pid/tid，或改为服务端按 opcode 查表解析，不再接受调用者提供的目标标识。

### 6.3 策略收紧

把 `vendor.perfservice` 移出 `app_api_service`，或保留可达性并在服务内加签名/特权权限校验——app 可达必须与真实鉴权成对出现。

### 6.4 释放操作加所有权校验

`perfLockRelease*` 应校验 handle 归属调用 uid。

### 6.5 判界漏等号

`ResourceQueue::GetNode` 的比较改为 `core >= getTotalNumCores()`，并在分配点按数组长度校验。补丁见 [`patches/01-getnode-bound-fix.patch`](patches/01-getnode-bound-fix.patch)。

---

## 7. 说明与边界

- **不主张提权。** 写入能力是越权与可用性影响，非内存安全问题（§3.5 除外，见下）。
- **未做堆布局 → 代码执行，不声称可利用。** §3.5 的越界写是 ~40 字节、部分受调用者影响的单个结构体，在 root 进程内；走到代码执行需要堆整理，这一步未尝试。
- **§3.5 与机型相关。** 触发条件为 perf 配置声明 `TotalNumCores ≤ 7`。**测试设备 `TotalNumCores = 8`，本机不可达**——在那类设备上复现失败是预期结果，不是本报告错误。Qualcomm 有搭载同款 HAL/optab 代码的 4 核与 6 核器件。
- **§5.4 的 SQL 落点未验证。** 仅确认服务端鉴权缺失与请求可达；本机 UX engine 路径被属性门控关闭，`-1` 返回值表明落点未执行。
- **可写值受配置表约束。** 节点路径模板来自只读配置分区，调用者只提供值；无路径注入能力。
- **补丁均未编译、未运行。** 见 [`patches/README.md`](patches/README.md) 的验证状态。
- 全部验证命令非破坏性；测试期间设备无崩溃、无重启。

---

## 8. 披露信息

| 项目 | 内容 |
|---|---|
| 报告性质 | 公开披露，含可编译的验证程序（`poc/`） |
| 主张范围 | 越权写入原语（任意目标 pid、整机旋钮）、跨进程调度态改写、服务端鉴权系统性缺失、`ResourceQueue::GetNode` 越界写。**不主张提权** |
| 报告日期 | 2026-10-05（形成结论） |
| 公开日期 | 2026-10-06 |
| 后续动作 | 本版为公开披露，不占用私有披露窗口；如需协商执行窗口或厂商沟通，请通过 SECURITY.md 中的渠道 |

---

## 附录 · 复现要点

**Parcel 布局**：`BnPerfManager::onTransact` 按 `duration, len, len, data…` 读取（第二个 `len` 是 `writeInt32Array` 写入的数组长度）：

```java
writeInterfaceToken("com.qualcomm.qti.IPerfManager");
writeInt(duration);              // 毫秒；0 = 持有直到释放
writeInt(len);                   // 占位
writeIntArray(new int[]{ opcode, target_pid });   // 写 len + 载荷
transact(4, ...);
reply.readException();
int handle = reply.readInt();
```

**两个坑**：

1. `argorder`：parcel **必须**把 `duration` 放在数组长度之前，否则服务端把 `duration` 读成小值，锁在毫秒级内过期。
2. 目标观测：受害者的节点要从 **shell 侧进程**读——调用应用自己打不开。

`poc/PerfservicePrivilegePoC.java` 为**单文件独立 PoC**（仅原始 binder，不依赖 Qualcomm 客户端库），对 `android-34` 用 `javac` 编译通过，全部序列以 logcat 标签 `PERFPOC` 打印。§5 的结果是由同一序列嵌入长期探针产生，即该文件的逻辑是已验证路径；独立文件本身是编译验证，供复现用。

---

## 算力赞助 / API Support

本报告的所有验证由作者的自有 API 额度完成。设备审计、大规模反汇编与全量静态扫描都是持续消耗——**独立安全研究没有薪资，只有额度**。

如果你认可这类公开、可复现、明确标注边界的研究，欢迎赞助，用于补充以下消耗：

- 大语言模型 API 调用额度（静态审计的交叉验证与代码审阅）
- 反汇编与二进制分析算力
- 测试设备获取

**赞助渠道**

- 爱发电：**https://afdian.com/a/iskoishi**

如果爱发电之外还有更方便的方式（PayPal / 加密货币 / 其他），也可以直接联系我；渠道会同步更新到这里。

**赞助不影响本报告的任何结论。** 本报告的全部结论与 PoC 均已公开，赞助只用于支持后续研究继续存在，不换取优先披露、不换取未公开内容、不换取范围变更。
