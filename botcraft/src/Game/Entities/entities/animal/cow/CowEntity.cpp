#include "botcraft/Game/Entities/entities/animal/cow/CowEntity.hpp"

#if PROTOCOL_VERSION > 769 /* > 1.21.4 */
#include <mutex>
#endif

namespace Botcraft
{
#if PROTOCOL_VERSION > 769 /* > 1.21.4 */
    const std::array<std::string, CowEntity::metadata_count> CowEntity::metadata_names{ {
            "data_variant_id",
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
            "data_sound_variant_id",
#endif
    } };
#endif

    CowEntity::CowEntity()
    {
#if PROTOCOL_VERSION < 770 /* < 1.21.5 */
        // Initialize all attributes with default values
        attributes.insert({ EntityAttribute::Type::MaxHealth, EntityAttribute(EntityAttribute::Type::MaxHealth, 10.0) });
        attributes.insert({ EntityAttribute::Type::MovementSpeed, EntityAttribute(EntityAttribute::Type::MovementSpeed, 0.2) });
#endif
#if PROTOCOL_VERSION > 769 /* > 1.21.4 */
        SetDataVariantId(0);
#endif
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
        SetDataSoundVariantId(0);
#endif
    }

    CowEntity::~CowEntity()
    {

    }


    std::string CowEntity::GetName() const
    {
        return "cow";
    }

    EntityType CowEntity::GetType() const
    {
        return EntityType::Cow;
    }


    std::string CowEntity::GetClassName()
    {
        return "cow";
    }

    EntityType CowEntity::GetClassType()
    {
        return EntityType::Cow;
    }

#if PROTOCOL_VERSION > 769 /* > 1.21.4 */
    ProtocolCraft::Json::Value CowEntity::Serialize() const
    {
        ProtocolCraft::Json::Value output = AbstractCowEntity::Serialize();
        output["metadata"]["data_variant_id"] = GetDataVariantId();
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
        output["metadata"]["data_sound_variant_id"] = GetDataSoundVariantId();
#endif

        return output;
    }

    void CowEntity::SetMetadataValue(const int index, const std::any& value)
    {
        if (index < hierarchy_metadata_count)
        {
            AbstractCowEntity::SetMetadataValue(index, value);
        }
        else if (index - hierarchy_metadata_count < metadata_count)
        {
            std::scoped_lock<std::shared_mutex> lock(entity_mutex);
            metadata[metadata_names[index - hierarchy_metadata_count]] = value;
        }
    }


    int CowEntity::GetDataVariantId() const
    {
        std::shared_lock<std::shared_mutex> lock(entity_mutex);
        return std::any_cast<int>(metadata.at("data_variant_id"));
    }
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
    int CowEntity::GetDataSoundVariantId() const
    {
        std::shared_lock<std::shared_mutex> lock(entity_mutex);
        return std::any_cast<int>(metadata.at("data_sound_variant_id"));
    }
#endif


    void CowEntity::SetDataVariantId(const int data_variant_id)
    {
        std::scoped_lock<std::shared_mutex> lock(entity_mutex);
        metadata["data_variant_id"] = data_variant_id;
    }
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
    void CowEntity::SetDataSoundVariantId(const int data_sound_variant_id)
    {
        std::scoped_lock<std::shared_mutex> lock(entity_mutex);
        metadata["data_sound_variant_id"] = data_sound_variant_id;
    }
#endif
#endif


    double CowEntity::GetWidthImpl() const
    {
        return 0.9;
    }

    double CowEntity::GetHeightImpl() const
    {
        return 1.4;
    }

}
