
import matplotlib.pyplot as plt
import numpy as np

benchmarks = [
    'blue\n-rdma', 
    'nvdla-1', 
    'nvdla-2',
    'nvdla-3',
    'nantu-\ncket-1', 
    'nantu-\ncket-2', 
    'coru-\nndum', 
    "mini-\nmap", 
    "ispd\n-fpga02",
    "hardcaml\n-ntt"
]

baseline = [3.6, 5.2, 5.0,   6.0, 1.7, 1.8,     3.6,  3.8,   3.9,    3.3 ]
slack   = [0.0, 0.0, -0.16, 0.0, 0.0, -0.033, -0.31, -0.39, -0.83, -0.78]

baseline_freq = [1000.0 / x for x in baseline]
our_freq = [1000.0 / (x - y) for x, y in zip(baseline, slack)]


bar_width = 20
bar_dist = 80

index = np.arange(len(benchmarks))  # 每个类别的 x 位置
index = index * bar_dist

color1 = (255.0/255, 208.0/255, 111.0/255)
color2 = (231.0/255, 98.0/255, 84.0/255)

# aspect ratio
plt.figure(figsize=(10, 6))
# 创建两个柱子
plt.bar(index - bar_width / 2 - 0.25, baseline_freq, bar_width, label='Vivado', color=color1)
plt.bar(index + bar_width / 2 + 0.25, our_freq, bar_width, label='Our Flow', color=color2)

ratios = [x / y for x, y in zip(our_freq, baseline_freq)]
avg_ratio = sum(ratios) / len(ratios)
print(f'Avg ratio: {avg_ratio:.2f} Degradation: {1.0 - avg_ratio:.2f}')
for i in range(len(benchmarks)):
    ratio = our_freq[i] / baseline_freq[i]
    ratio = round(ratio, 2)
    plt.text(index[i] - bar_width / 2 - 5.0 , baseline_freq[i], '1.0', ha='center', va='bottom', fontsize=10, color='black')
    if ratio == 1.0:
        plt.text(index[i] + bar_width / 2 + 7.0 , our_freq[i],
                f'{ratio:.1f}', ha='center', va='bottom', fontsize=10, color='black')
    else:
        plt.text(index[i] + bar_width / 2 + 7.0 , our_freq[i],
                f'{ratio:.2f}', ha='center', va='bottom', fontsize=10, color='black')
    
# 添加标题和标签
#plt.xlabel('类别')
plt.ylabel('Frequency(MHz)', fontsize=15)

# 设置 x 轴的刻度为类别
plt.xticks(index, benchmarks, fontsize=12)

# 添加图例
plt.legend(fontsize=14)

# 显示图表
#plt.show()
plt.savefig('freq_eval.png')