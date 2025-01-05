
import matplotlib.pyplot as plt
import numpy as np

categories = ['blue\n-rdma', 'nvdla\n-small', 'nvdla\n-med', 'nvdla\n-large', 'ntt\n-small', 'ntt\n-large', 'corundum', "ispd\n-fpga02", "minimap"]
values1 = [285.7, 250.0, 188.6, 196.0, 555.5, 555.5, 277.7, 256.4, 285.7]
values2 = [277.7, 250.0, 188.6, 196.0, 555.5, 526.3, 263.1, 222.2, 250.0]

bar_width = 20
bar_dist = 80

index = np.arange(len(categories))  # 每个类别的 x 位置
index = index * bar_dist

color1 = (255.0/255, 208.0/255, 111.0/255)
color2 = (231.0/255, 98.0/255, 84.0/255)

# aspect ratio
plt.figure(figsize=(10, 6))
# 创建两个柱子
plt.bar(index - bar_width / 2 - 0.25, values1, bar_width, label='Vivado', color=color1)
plt.bar(index + bar_width / 2 + 0.25, values2, bar_width, label='Our Flow', color=color2)

for i in range(len(categories)):
    ratio = values2[i] / values1[i]
    
    plt.text(index[i] + bar_width / 2 + 0.4 , values2[i],
             f'{ratio:.2f}', ha='center', va='bottom', fontsize=10, color='black')
    
# 添加标题和标签
#plt.xlabel('类别')
plt.ylabel('Frequency(MHz)', fontsize=15)

# 设置 x 轴的刻度为类别
plt.xticks(index, categories, fontsize=12)

# 添加图例
plt.legend(fontsize=14)

# 显示图表
plt.show()
plt.savefig('freq_eval.png')