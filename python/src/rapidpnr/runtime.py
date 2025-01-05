import matplotlib.pyplot as plt
import numpy as np

# name = ["spam-filter", "fireflyv2",    "corundum", "ispd-fpga02", "nvdla-small", "blue-rdma", "nvdla-medium", "nvdla-large", "minimap", "ntt-large", "ispd-fpga04"]
# size = [        14774,      144477,        148611,        154644,        188559,      239072,         245111,        288403,    296608,      362976,      390773]
# runtime = [      302,         1550,          2316,          1970,          1676,        1946,           1676,          2204,      3636,        3472,       11676]

size = [        14774,      114477,        154644,        188559,      239072,         288403,    296608,      362976,      390773]
runtime = [      302,         1550,          1970,          1676,        1946,           2204,      3636,        3472,       11676]

# 设置图形大小
plt.figure(figsize=(6, 6))

# 创建第一个Y轴 (lut_count 和 ff_count)
fig, ax = plt.subplots(figsize=(6, 6))
ax.grid(True)
ax.set_xlabel('Primitive Cell Count', fontsize=15)
ax.set_ylabel('Runtime (sec)', fontsize=15)

# 绘制 LUT Count
line1, = ax.plot(size, runtime, 'r-^', label='Runtime', markerfacecolor='red', markersize=10)
ax.ticklabel_format(style='sci', axis='x', scilimits=(0, 0))  # x轴使用科学计数法
ax.ticklabel_format(style='sci', axis='y', scilimits=(0, 0))  # y轴使用科学计数法

plt.title('Scaling of Physical Design Runtime', fontsize=15)
fig.tight_layout()
plt.show()